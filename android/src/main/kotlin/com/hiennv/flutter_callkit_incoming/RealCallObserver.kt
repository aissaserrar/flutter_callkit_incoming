package com.hiennv.flutter_callkit_incoming

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * Detects a real (cellular or other-VoIP) incoming phone call while an order
 * ring is active, so the order ring can be dismissed immediately and never
 * re-surface during/after the phone call.
 *
 * Because the host app uses a **self-managed** [CallkitConnectionService],
 * [TelephonyManager] call-state callbacks do **not** report the app's own
 * order ring back to it — so any `CALL_STATE_RINGING` / `CALL_STATE_OFFHOOK`
 * event observed here is a genuine external call.
 *
 * Two strategies, used together ("belt and suspenders"):
 *
 *  1. **`READ_PHONE_STATE`** (preferred). When granted, a real
 *     [TelephonyManager] call-state listener fires the moment an external call
 *     rings/answers. Most reliable.
 *  2. **Audio-focus heuristic** (permission-free fallback). While a ring is
 *     active we request `AUDIOFOCUS_GAIN_TRANSIENT` on the ring stream; a real
 *     incoming call yanks focus from us, which surfaces as
 *     `AUDIOFOCUS_LOSS` / `AUDIOFOCUS_LOSS_TRANSIENT` and fires the callback.
 *     Used automatically when `READ_PHONE_STATE` is not granted. Slightly more
 *     prone to over-dismissal (any competing audio app), which is acceptable —
 *     the chosen behavior is "dismiss the order ring immediately".
 *
 * The observer fires [onRealCall] **exactly once** per [start] and then stops
 * itself. [stop] is idempotent and null-safe.
 */
class RealCallObserver(
    private val context: Context,
    private val onRealCall: () -> Unit,
) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var fired = false
    @Volatile private var started = false

    // --- Telephony strategy state -------------------------------------------------
    private var legacyPhoneStateListener: PhoneStateListener? = null
    @Volatile private var modernTelephonyCallback: Any? = null // TelephonyCallback (API 31+)
    private val telephonyExecutor = Executors.newSingleThreadExecutor()

    // --- Audio-focus strategy state -----------------------------------------------
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null // API 26+
    @Suppress("DEPRECATION")
    private var legacyAudioFocusListener: AudioManager.OnAudioFocusChangeListener? = null

    /**
     * Begins observing. Re-entrant: a second [start] while already started is a
     * no-op (the first registration is kept). Resolves which strategy to apply
     * based on the `READ_PHONE_STATE` grant.
     */
    fun start() {
        if (started) return
        started = true
        fired = false
        Log.d(TAG, "start — observing for a real incoming call")

        if (hasReadPhoneState()) {
            startTelephonyStrategy()
        } else {
            Log.d(TAG, "READ_PHONE_STATE not granted — using audio-focus fallback")
        }
        // Always also arm the audio-focus fallback: it covers OEMs that don't
        // deliver phone-state callbacks to self-managed ConnectionService apps
        // promptly, and it's the only signal when the permission is missing.
        startAudioFocusStrategy()
    }

    /** Stops observing and releases all listeners/focus. Safe to call any time. */
    fun stop() {
        if (!started) {
            return
        }
        started = false
        Log.d(TAG, "stop")
        stopTelephonyStrategy()
        stopAudioFocusStrategy()
    }

    // ---------------------------------------------------------------------------
    // Telephony strategy
    // ---------------------------------------------------------------------------

    private fun hasReadPhoneState(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION", "UNUSED_VARIABLE")
    private fun startTelephonyStrategy() {
        val tm = appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                startModernTelephony(tm)
            } else {
                startLegacyTelephony(tm)
            }
        } catch (e: SecurityException) {
            // READ_PHONE_STATE revoked between check and register — fall back to
            // audio focus, which is already armed.
            Log.w(TAG, "telephony strategy registration denied: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "telephony strategy failed: ${e.message}")
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun startModernTelephony(tm: TelephonyManager) {
        val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                if (state == TelephonyManager.CALL_STATE_RINGING ||
                    state == TelephonyManager.CALL_STATE_OFFHOOK
                ) {
                    Log.d(TAG, "telephony callback → real call state=$state")
                    fire()
                }
            }
        }
        modernTelephonyCallback = callback
        tm.registerTelephonyCallback(telephonyExecutor, callback)
    }

    @Suppress("DEPRECATION")
    private fun startLegacyTelephony(tm: TelephonyManager) {
        val listener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                if (state == TelephonyManager.CALL_STATE_RINGING ||
                    state == TelephonyManager.CALL_STATE_OFFHOOK
                ) {
                    Log.d(TAG, "phone-state listener → real call state=$state")
                    fire()
                }
            }
        }
        legacyPhoneStateListener = listener
        tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
    }

    private fun stopTelephonyStrategy() {
        val tm = appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        if (tm != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    (modernTelephonyCallback as? TelephonyCallback)?.let {
                        tm.unregisterTelephonyCallback(it)
                    }
                } else {
                    @Suppress("DEPRECATION")
                    legacyPhoneStateListener?.let { tm.listen(it, PhoneStateListener.LISTEN_NONE) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "telephony unregister failed: ${e.message}")
            }
        }
        modernTelephonyCallback = null
        legacyPhoneStateListener = null
    }

    // ---------------------------------------------------------------------------
    // Audio-focus strategy
    // ---------------------------------------------------------------------------

    private fun startAudioFocusStrategy() {
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        audioManager = am

        val attrs = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val listener = AudioManager.OnAudioFocusChangeListener { change ->
                // A real incoming call claims the ring stream and takes focus
                // from us. TRANSIENT loss is the usual signal for a ringing call;
                // permanent LOSS covers an answered/active call.
                if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ||
                    change == AudioManager.AUDIOFOCUS_LOSS
                ) {
                    Log.d(TAG, "audio focus lost ($change) → likely real call")
                    fire()
                }
            }
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(listener)
                .build()
            audioFocusRequest = request
            am.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            val listener = AudioManager.OnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ||
                    change == AudioManager.AUDIOFOCUS_LOSS
                ) {
                    Log.d(TAG, "audio focus lost ($change) → likely real call")
                    fire()
                }
            }
            legacyAudioFocusListener = listener
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                listener,
                AudioManager.STREAM_RING,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            )
        }
    }

    private fun stopAudioFocusStrategy() {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                legacyAudioFocusListener?.let { am.abandonAudioFocus(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "abandon audio focus failed: ${e.message}")
        }
        audioFocusRequest = null
        legacyAudioFocusListener = null
        audioManager = null
    }

    // ---------------------------------------------------------------------------
    // Fire-once trigger
    // ---------------------------------------------------------------------------

    /**
     * Fires the [onRealCall] callback a single time on the main thread, then
     * stops observing. Subsequent invocations are no-ops.
     */
    private fun fire() {
        if (fired) return
        fired = true
        mainHandler.post {
            try {
                onRealCall()
            } catch (e: Exception) {
                Log.e(TAG, "onRealCall callback threw", e)
            }
            stop()
        }
    }

    companion object {
        private const val TAG = "RealCallObserver"
    }
}
