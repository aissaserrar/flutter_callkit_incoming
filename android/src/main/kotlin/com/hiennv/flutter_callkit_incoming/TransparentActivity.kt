package com.hiennv.flutter_callkit_incoming

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log

class TransparentActivity : Activity() {

    companion object {
        var isVisible: Boolean = false

        fun getIntent(context: Context, action: String, data: Bundle?): Intent {
            val intent = Intent(context, TransparentActivity::class.java)
            intent.action = action
            intent.putExtra("data", data)
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return intent
        }
    }


    override fun onStart() {
        super.onStart()
        setVisible(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val action = intent.action
        if (action == null) {
            Log.w("TransparentActivity", "Intent action is null, finishing activity")
            finishTask()
            return
        }

        val data = intent.getBundleExtra("data")

        val broadcastIntent = CallkitIncomingBroadcastReceiver.getIntent(this, action, data)
        broadcastIntent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        sendBroadcast(broadcastIntent)

        val activityIntent = AppUtils.getAppIntent(this, action, data)
        startActivity(activityIntent)

        // finishAndRemoveTask, not finish(): launched from the singleInstance
        // ring activity this trampoline is forced into its own task (default
        // package affinity). A plain finish() leaves that task lingering empty,
        // and Android re-surfaces the app's main task when it notices the empty
        // task — the app "reopens itself" seconds after the user switches away.
        finishTask()
        overridePendingTransition(0, 0)
    }

    private fun finishTask() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAndRemoveTask()
        } else {
            finish()
        }
    }
}
