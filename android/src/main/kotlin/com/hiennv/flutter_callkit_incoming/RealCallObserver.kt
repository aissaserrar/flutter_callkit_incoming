package com.hiennv.flutter_callkit_incoming

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
 * overlaps the phone call.
 *
 * **Telephony-only.** Listens to the system [TelephonyManager] call-state
 * stream; any `CALL_STATE_RINGING` / `CALL_STATE_OFFHOOK` event fires
 * [onRealCall]. Requires the `READ_PHONE_STATE` runtime permission, which the
 * host app requests separately. When the permission is not granted the observer
 * is a no-op — acceptable because the order ring is now a *notification* (no
 * Telecom `ConnectionService`), so it can no longer block or be blocked by a
 * real incoming call; the two ringtones may briefly overlap, that is all.
 *
 * The previous build also armed an always-on **audio-focus heuristic** as a
 * permission-free fallback; that strategy dismissed the order ring whenever any
 * app took audio focus (music, navigation prompts, voice notes) and was the
 * root cause of the "ring sometimes doesn't fire" reports. It has been removed.
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

    /**
     * Begins observing. Re-entrant: a second [start] while already started is a
     * no-op (the first registration is kept). Does nothing when
     * `READ_PHONE_STATE` is not granted.
     */
    fun start() {
        if (started) return
        started = true
        fired = false
        if (hasReadPhoneState()) {
            Log.d(TAG, "start — observing for a real incoming call")
            startTelephonyStrategy()
        } else {
            // No telephony permission and no audio-focus fallback: nothing to
            // observe. The order ring simply plays to completion; it cannot
            // block the real call because it is no longer a Telecom call.
            started = false
            Log.d(TAG, "start — READ_PHONE_STATE not granted; observer idle")
        }
    }

    /** Stops observing and releases the listener. Safe to call any time. */
    fun stop() {
        if (!started) {
            return
        }
        started = false
        Log.d(TAG, "stop")
        stopTelephonyStrategy()
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
            ?: run {
                started = false
                return
            }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                startModernTelephony(tm)
            } else {
                startLegacyTelephony(tm)
            }
        } catch (e: SecurityException) {
            // READ_PHONE_STATE revoked between check and register — observer is
            // effectively disabled; ring will play to completion.
            started = false
            Log.w(TAG, "telephony strategy registration denied: ${e.message}")
        } catch (e: Exception) {
            started = false
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
