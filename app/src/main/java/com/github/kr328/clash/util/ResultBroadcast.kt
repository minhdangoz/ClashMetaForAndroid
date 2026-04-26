package com.github.kr328.clash.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.github.kr328.clash.service.util.sendBroadcastSelf

/**
 * Central place for sending automation results back to external callers.
 *
 * Every action that can be triggered externally (connect, disconnect, profile load)
 * sends BOTH:
 *   1. A local broadcast  → so other components in the same process can react
 *   2. An explicit result  → so the originating Activity can setResult() for
 *      startActivityForResult() callers
 *
 * Broadcast action  : com.cmcmedia.clash.action.AUTOMATION_RESULT
 * Extras:
 *   EXTRA_SUCCESS   (Boolean) – overall success
 *   EXTRA_EVENT     (String)  – one of: PROFILE_CREATED, PROXY_STARTED,
 *                                       PROXY_STOPPED, HEALTH_CHECK
 *   EXTRA_MESSAGE   (String)  – human-readable detail
 *   EXTRA_HEALTH_OK (Boolean) – present only when event == PROXY_STARTED
 *   EXTRA_LATENCY   (Long)    – ms, present only when HEALTH_OK is true
 */
object ResultBroadcast {

    const val ACTION_RESULT   = "com.cmcmedia.clash.action.AUTOMATION_RESULT"

    const val EXTRA_SUCCESS   = "success"
    const val EXTRA_EVENT     = "event"
    const val EXTRA_MESSAGE   = "message"
    const val EXTRA_HEALTH_OK = "health_ok"
    const val EXTRA_LATENCY   = "latency_ms"

    object Event {
        const val PROFILE_CREATED = "PROFILE_CREATED"
        const val PROXY_STARTED   = "PROXY_STARTED"
        const val PROXY_STOPPED   = "PROXY_STOPPED"
        const val HEALTH_CHECK    = "HEALTH_CHECK"
    }

    /** Send a result broadcast visible within this app's process. */
    fun Context.sendResult(
        event: String,
        success: Boolean,
        message: String,
        healthOk: Boolean? = null,
        latencyMs: Long? = null,
    ) {
        AutoLog.i("ResultBroadcast", "event=$event success=$success msg=$message health=$healthOk latency=${latencyMs}ms")

        val intent = Intent(ACTION_RESULT).apply {
            `package` = packageName
            putExtra(EXTRA_SUCCESS,  success)
            putExtra(EXTRA_EVENT,    event)
            putExtra(EXTRA_MESSAGE,  message)
            healthOk?.let  { putExtra(EXTRA_HEALTH_OK, it) }
            latencyMs?.let { putExtra(EXTRA_LATENCY,   it) }
        }

        sendBroadcastSelf(intent)
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
