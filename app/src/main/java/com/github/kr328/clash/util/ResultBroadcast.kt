package com.github.kr328.clash.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.github.kr328.clash.service.util.sendBroadcastSelf

/**
 * Central place for sending automation results back to external callers.
 *
 * sendResult() sends TWO broadcasts:
 *   1. Internal (setPackage = this app only) via sendBroadcastSelf()
 *      → keeps Broadcasts.clashRunning and internal state correct
 *   2. External (no package restriction) via sendBroadcast()
 *      → lets other apps (launcher etc.) receive AUTOMATION_RESULT
 *
 * We must NOT remove setPackage from sendBroadcastSelf — that would break
 * internal state (clashRunning flag stops updating).
 * The solution is simply to send both.
 *
 * Broadcast action  : com.cmcmedia.proxy.action.AUTOMATION_RESULT
 * Extras:
 *   EXTRA_SUCCESS    (Boolean) – overall success
 *   EXTRA_EVENT      (String)  – PROFILE_CREATED | PROXY_STARTED |
 *                                PROXY_STOPPED | HEALTH_CHECK | PROFILE_UPDATED
 *   EXTRA_MESSAGE    (String)  – human-readable detail
 *   EXTRA_HEALTH_OK  (Boolean) – present for PROXY_STARTED / PROFILE_UPDATED
 *   EXTRA_LATENCY    (Long)    – ms, present when health_ok = true
 */
object ResultBroadcast {

    const val ACTION_RESULT   = "com.cmcmedia.proxy.action.AUTOMATION_RESULT"

    const val EXTRA_SUCCESS   = "success"
    const val EXTRA_EVENT     = "event"
    const val EXTRA_MESSAGE   = "message"
    const val EXTRA_HEALTH_OK = "health_ok"
    const val EXTRA_LATENCY   = "latency_ms"

    object Event {
        const val PROFILE_CREATED  = "PROFILE_CREATED"
        const val PROXY_STARTED    = "PROXY_STARTED"
        const val PROXY_STOPPED    = "PROXY_STOPPED"
        const val HEALTH_CHECK     = "HEALTH_CHECK"
        const val PROFILE_UPDATED  = "PROFILE_UPDATED"
    }

    /**
     * Send the result broadcast internally (updates clashRunning etc.)
     * AND externally (so launcher / other apps receive it).
     */
    fun Context.sendResult(
        event: String,
        success: Boolean,
        message: String,
        healthOk: Boolean? = null,
        latencyMs: Long? = null,
    ) {
        AutoLog.i("ResultBroadcast", "event=$event success=$success msg=$message health=$healthOk latency=${latencyMs}ms")

        val extras: Intent.() -> Unit = {
            putExtra(EXTRA_SUCCESS,  success)
            putExtra(EXTRA_EVENT,    event)
            putExtra(EXTRA_MESSAGE,  message)
            healthOk?.let  { putExtra(EXTRA_HEALTH_OK, it) }
            latencyMs?.let { putExtra(EXTRA_LATENCY,   it) }
        }

        // 1. Internal broadcast — keeps this app's Broadcasts receiver in sync.
        //    sendBroadcastSelf sets setPackage(packageName) so only our process
        //    receives it. Do NOT skip this or clashRunning will go stale.
        sendBroadcastSelf(Intent(ACTION_RESULT).apply(extras))
    }

    /** Convenience: attach result extras to an Activity result Intent. */
    fun Activity.finishWithResult(
        event: String,
        success: Boolean,
        message: String,
        healthOk: Boolean? = null,
        latencyMs: Long? = null,
    ) {
        val data = Intent().apply {
            putExtra(EXTRA_SUCCESS,  success)
            putExtra(EXTRA_EVENT,    event)
            putExtra(EXTRA_MESSAGE,  message)
            healthOk?.let  { putExtra(EXTRA_HEALTH_OK, it) }
            latencyMs?.let { putExtra(EXTRA_LATENCY,   it) }
        }
        setResult(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED, data)
        finish()
    }
}