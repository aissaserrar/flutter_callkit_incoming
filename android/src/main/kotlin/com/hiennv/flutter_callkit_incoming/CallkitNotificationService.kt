package com.hiennv.flutter_callkit_incoming

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlin.math.abs

class CallkitNotificationService : Service() {

    companion object {

        private const val FALLBACK_NOTIFICATION_ID = 9999
        private const val FALLBACK_CHANNEL_ID = "callkit_incoming_fallback"

        private val ActionForeground = listOf(
            CallkitConstants.ACTION_CALL_START,
            CallkitConstants.ACTION_CALL_ACCEPT
        )

        // Incoming ring timeout lives here (not in the noHistory activity) so it survives
        // the activity being hidden/destroyed by the keyguard.
        private val incomingTimeoutHandler = Handler(Looper.getMainLooper())
        private var incomingTimeoutRunnable: Runnable? = null

        fun scheduleIncomingTimeout(context: Context, data: Bundle) {
            cancelIncomingTimeout()
            val appContext = context.applicationContext
            val duration = data.getLong(CallkitConstants.EXTRA_CALLKIT_DURATION, 0L)
            val startCall = data.getLong(
                CallkitNotificationManager.EXTRA_TIME_START_CALL,
                System.currentTimeMillis()
            )
            val delay = (duration - abs(System.currentTimeMillis() - startCall))
                .coerceAtLeast(0L)
            val runnable = Runnable {
                incomingTimeoutRunnable = null
                appContext.sendBroadcast(
                    CallkitIncomingBroadcastReceiver.getIntentTimeout(appContext, data)
                )
            }
            incomingTimeoutRunnable = runnable
            incomingTimeoutHandler.postDelayed(runnable, delay)
        }

        fun cancelIncomingTimeout() {
            incomingTimeoutRunnable?.let { incomingTimeoutHandler.removeCallbacks(it) }
            incomingTimeoutRunnable = null
        }

        // Watches for a real (cellular/other VoIP) incoming call while an order
        // ring is active, so the ring is dismissed immediately instead of
        // re-surfacing during/after the phone call. Lives here, next to the
        // incoming-timeout, because both must survive the no-history incoming
        // activity being hidden/destroyed by the keyguard.
        private var realCallObserver: RealCallObserver? = null

        /**
         * Begins watching for a real incoming call while the ring for [data]
         * is active. When one is detected, the ring is torn down via the
         * existing TIMEOUT path (clears the notification, rejects the self-
         * managed Telecom connection, shows the missed-order notification).
         */
        fun startRealCallObserver(context: Context, data: Bundle) {
            cancelRealCallObserver()
            val appContext = context.applicationContext
            realCallObserver = RealCallObserver(appContext) {
                dismissOnRealCall(appContext)
            }.also { it.start() }
        }

        /**
         * Stops the real-call observer without firing it. Call on every terminal
         * path (accept/decline/ended/timeout) so the listener never outlives the
         * ring. Idempotent.
         */
        fun cancelRealCallObserver() {
            realCallObserver?.stop()
            realCallObserver = null
        }

        /**
         * Teardown invoked when a real incoming call is detected while an order
         * ring is active. Reuses the TIMEOUT broadcast path so all of the
         * existing cleanup (notification clear, Telecom reject, missed-order
         * notification, Flutter event) runs unchanged for every active call.
         */
        private fun dismissOnRealCall(context: Context) {
            Log.d("CallkitNotificationService", "real call detected → dismissing order ring(s)")
            // Stop the ringtone/vibration immediately, before the broadcast
            // round-trip, so the driver hears the real call at once.
            FlutterCallkitIncomingPlugin.getInstance()?.getCallkitSoundPlayerManager()?.stop()
            cancelIncomingTimeout()
            try {
                getDataActiveCalls(context).forEach { active ->
                    context.sendBroadcast(
                        CallkitIncomingBroadcastReceiver.getIntentTimeout(
                            context,
                            active.toBundle()
                        )
                    )
                }
            } catch (e: Exception) {
                Log.w("CallkitNotificationService", "dismissOnRealCall failed: ${e.message}")
            }
            stopService(context)
        }


        fun startServiceWithAction(context: Context, action: String, data: Bundle?) {
            val intent = Intent(context, CallkitNotificationService::class.java).apply {
                this.action = action
                putExtra(CallkitConstants.EXTRA_CALLKIT_INCOMING_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && intent.action == CallkitConstants.ACTION_CALL_INCOMING) {
                // The incoming alert must always foreground, independent of the
                // callingShow flag (which governs only the ongoing/accepted notification).
                try {
                    ContextCompat.startForegroundService(context, intent)
                } catch (e: Exception) {
                    context.startService(intent)
                }
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && intent.action in ActionForeground) {
                data?.let {
                    if(it.getBoolean(CallkitConstants.EXTRA_CALLKIT_CALLING_SHOW, true)) {
                        ContextCompat.startForegroundService(context, intent)
                    }else {
                        context.startService(intent)
                    }
                }
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, CallkitNotificationService::class.java)
            context.stopService(intent)
        }

    }

    // Get notification manager dynamically to handle plugin lifecycle properly
    private fun getCallkitNotificationManager(): CallkitNotificationManager? {
        return FlutterCallkitIncomingPlugin.getInstance()?.getCallkitNotificationManager()
    }


    override fun onCreate() {
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CallkitConstants.ACTION_CALL_INCOMING) {
            intent.getBundleExtra(CallkitConstants.EXTRA_CALLKIT_INCOMING_DATA)
                ?.let { data ->
                    val nm = getCallkitNotificationManager()
                    nm?.createNotificationChanel(data)
                    val incoming = nm?.getIncomingNotification(data, ownTimeout = false)
                    if (incoming != null) {
                        startCallForeground(incoming.id, incoming.notification)
                        nm.startIncomingRing(data)
                        scheduleIncomingTimeout(this, data)
                        // Arm real-call detection so the ring steps aside the
                        // instant a real phone call arrives (see RealCallObserver).
                        startRealCallObserver(this, data)
                    } else {
                        // Never leave a startForegroundService() without a startForeground()
                        // call, or the OS kills the process. The manager may be momentarily
                        // null if the service starts before the Flutter engine re-attaches.
                        startCallForeground(
                            FALLBACK_NOTIFICATION_ID,
                            buildFallbackNotification(data)
                        )
                    }
                }
        }
        if (intent?.action === CallkitConstants.ACTION_CALL_START) {
            intent.getBundleExtra(CallkitConstants.EXTRA_CALLKIT_INCOMING_DATA)
                ?.let {
                    if(it.getBoolean(CallkitConstants.EXTRA_CALLKIT_CALLING_SHOW, true)) {
                        getCallkitNotificationManager()?.createNotificationChanel(it)
                        showOngoingCallNotification(it)
                    }else {
                        stopSelf()
                    }
                }
        }
        if (intent?.action === CallkitConstants.ACTION_CALL_ACCEPT) {
            cancelIncomingTimeout()
            cancelRealCallObserver()
            intent.getBundleExtra(CallkitConstants.EXTRA_CALLKIT_INCOMING_DATA)
                ?.let {
                    getCallkitNotificationManager()?.clearIncomingNotification(it, true)
                    if (it.getBoolean(CallkitConstants.EXTRA_CALLKIT_CALLING_SHOW, true)) {
                        showOngoingCallNotification(it)
                    }else {
                        stopSelf()
                    }
                }
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun showOngoingCallNotification(bundle: Bundle) {

        val callkitNotification =
            getCallkitNotificationManager()?.getOnGoingCallNotification(bundle, false)
        if (callkitNotification != null) {
            startCallForeground(
                callkitNotification.id,
                callkitNotification.notification,
            )
        }
    }

    private fun startCallForeground(notificationId: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Kabsa Driver uses CallKit only as a visual/audio alert for new order
            // assignments; it never captures microphone or camera and is no longer a
            // Telecom phone call. `specialUse` is the honest FGS type for the
            // incoming-delivery-order ring; the subtype is declared in the manifest.
            startForeground(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(notificationId, notification)
        }
    }


    // Minimal ongoing notification used only when the manager isn't ready yet, so a
    // foreground start is never left dangling. It carries no ring/timeout of its own.
    private fun buildFallbackNotification(data: Bundle?): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                FALLBACK_CHANNEL_ID,
                "Incoming Call",
                NotificationManager.IMPORTANCE_HIGH
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
        val nameCaller = data?.getString(CallkitConstants.EXTRA_CALLKIT_NAME_CALLER, "") ?: ""
        return NotificationCompat.Builder(this, FALLBACK_CHANNEL_ID)
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle(if (nameCaller.isEmpty()) "Incoming Call" else nameCaller)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Don't destroy the notification manager here as it's shared across the app
        // The plugin will handle cleanup when all engines are detached
    }

    override fun onBind(p0: Intent?): IBinder? {
        return null
    }


    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Don't kill the FGS. The app might be closed by user but the call is still ongoing
    }
}
