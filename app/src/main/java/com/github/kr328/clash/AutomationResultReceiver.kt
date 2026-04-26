package com.github.kr328.clash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.github.kr328.clash.util.AutoLog
import com.github.kr328.clash.util.ResultBroadcast

/**
 * AutomationResultReceiver
 * ========================
 * Exported stub receiver that other apps can use as a reference for how to
 * receive com.cmcmedia.clash.action.AUTOMATION_RESULT broadcasts.
 *
 * You do NOT need to modify this class. External apps should declare their own
 * BroadcastReceiver registered for ACTION_RESULT.
 *
 * Example from an external app:
 *
 *   val filter = IntentFilter("com.cmcmedia.clash.action.AUTOMATION_RESULT")
 *   registerReceiver(myReceiver, filter)
 *
 *   // In myReceiver.onReceive():
 *   val success   = intent.getBooleanExtra("success", false)
 *   val event     = intent.getStringExtra("event")      // e.g. "PROXY_STARTED"
 *   val healthOk  = intent.getBooleanExtra("health_ok", false)
 *   val latencyMs = intent.getLongExtra("latency_ms", -1)
 *   val message   = intent.getStringExtra("message")
 */
class AutomationResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ResultBroadcast.ACTION_RESULT) return

        val success   = intent.getBooleanExtra(ResultBroadcast.EXTRA_SUCCESS, false)
        val event     = intent.getStringExtra(ResultBroadcast.EXTRA_EVENT) ?: "?"
        val message   = intent.getStringExtra(ResultBroadcast.EXTRA_MESSAGE) ?: ""
        val healthOk  = intent.getBooleanExtra(ResultBroadcast.EXTRA_HEALTH_OK, false)
        val latencyMs = intent.getLongExtra(ResultBroadcast.EXTRA_LATENCY, -1L)

        AutoLog.i(
            "AutoResultRx",
            "event=$event success=$success health=$healthOk latency=${latencyMs}ms msg=$message"
        )
    }
}
