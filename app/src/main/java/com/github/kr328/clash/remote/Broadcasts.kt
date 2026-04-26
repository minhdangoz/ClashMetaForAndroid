package com.github.kr328.clash.remote

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.github.kr328.clash.common.compat.registerReceiverCompat
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.util.ResultBroadcast
import java.util.UUID

class Broadcasts(private val context: Application) {
    interface Observer {
        fun onServiceRecreated()
        fun onStarted()
        fun onStopped(cause: String?)
        fun onProfileChanged()
        fun onProfileUpdateCompleted(uuid: UUID?)
        fun onProfileUpdateFailed(uuid: UUID?, reason: String?)
        fun onProfileLoaded()
    }

    var clashRunning: Boolean = false

    private var registered = false
    private val receivers = mutableListOf<Observer>()

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.`package` != context?.packageName)
                return

            when (intent?.action) {
                Intents.ACTION_SERVICE_RECREATED -> {
                    clashRunning = false
                    Log.d("[Broadcasts] SERVICE_RECREATED → clashRunning=false")
                    receivers.forEach { it.onServiceRecreated() }
                }
                Intents.ACTION_CLASH_STARTED -> {
                    clashRunning = true
                    Log.d("[Broadcasts] CLASH_STARTED → clashRunning=true")
                    receivers.forEach { it.onStarted() }
                }
                Intents.ACTION_CLASH_STOPPED -> {
                    clashRunning = false
                    Log.d("[Broadcasts] CLASH_STOPPED → clashRunning=false cause=${intent.getStringExtra(Intents.EXTRA_STOP_REASON)}")
                    receivers.forEach { it.onStopped(intent.getStringExtra(Intents.EXTRA_STOP_REASON)) }
                }
                Intents.ACTION_PROFILE_CHANGED -> {
                    Log.d("[Broadcasts] PROFILE_CHANGED")
                    receivers.forEach { it.onProfileChanged() }
                }
                Intents.ACTION_PROFILE_UPDATE_COMPLETED -> {
                    val uuid = runCatching {
                        UUID.fromString(intent.getStringExtra(Intents.EXTRA_UUID))
                    }.getOrNull()
                    Log.d("[Broadcasts] PROFILE_UPDATE_COMPLETED uuid=$uuid")
                    receivers.forEach { it.onProfileUpdateCompleted(uuid) }
                }
                Intents.ACTION_PROFILE_UPDATE_FAILED -> {
                    val uuid = runCatching {
                        UUID.fromString(intent.getStringExtra(Intents.EXTRA_UUID))
                    }.getOrNull()
                    val reason = intent.getStringExtra(Intents.EXTRA_FAIL_REASON)
                    Log.d("[Broadcasts] PROFILE_UPDATE_FAILED uuid=$uuid reason=$reason")
                    receivers.forEach { it.onProfileUpdateFailed(uuid, reason) }
                }
                Intents.ACTION_PROFILE_LOADED -> {
                    Log.d("[Broadcasts] PROFILE_LOADED")
                    receivers.forEach { it.onProfileLoaded() }
                }
                ResultBroadcast.ACTION_RESULT -> {
                    val success  = intent.getBooleanExtra(ResultBroadcast.EXTRA_SUCCESS, false)
                    val event    = intent.getStringExtra(ResultBroadcast.EXTRA_EVENT) ?: "?"
                    val msg      = intent.getStringExtra(ResultBroadcast.EXTRA_MESSAGE) ?: ""
                    val healthOk = intent.getBooleanExtra(ResultBroadcast.EXTRA_HEALTH_OK, false)
                    val latency  = intent.getLongExtra(ResultBroadcast.EXTRA_LATENCY, -1L)
                    Log.d("[Broadcasts] AUTOMATION_RESULT event=$event success=$success health=$healthOk latency=${latency}ms msg=$msg")
                }
            }
        }
    }

    fun addObserver(observer: Observer) {
        receivers.add(observer)
    }

    fun removeObserver(observer: Observer) {
        receivers.remove(observer)
    }

    /**
     * Register the broadcast receiver for the Application's full lifetime.
     * Called once from Remote.launch() → onVisibleChanged(true).
     *
     * Previously this was paired with unregister() on invisible, which caused
     * ACTION_CLASH_STARTED to be missed during the invisible window, leaving
     * clashRunning stale (false) on re-open even when clash was running.
     *
     * The receiver now stays registered permanently. Per-activity observers are
     * added/removed via addObserver/removeObserver in BaseActivity.onStart/onStop,
     * so UI callbacks still only fire when an activity is visible.
     */
    fun register() {
        if (registered) return

        try {
            context.registerReceiverCompat(broadcastReceiver, IntentFilter().apply {
                addAction(Intents.ACTION_SERVICE_RECREATED)
                addAction(Intents.ACTION_CLASH_STARTED)
                addAction(Intents.ACTION_CLASH_STOPPED)
                addAction(Intents.ACTION_PROFILE_CHANGED)
                addAction(Intents.ACTION_PROFILE_UPDATE_COMPLETED)
                addAction(Intents.ACTION_PROFILE_UPDATE_FAILED)
                addAction(Intents.ACTION_PROFILE_LOADED)
                addAction(ResultBroadcast.ACTION_RESULT)
            })

            // Initialise conservatively. The running service will immediately
            // send ACTION_CLASH_STARTED if clash is actually up, which sets
            // clashRunning = true within milliseconds.
            clashRunning = false
            registered = true
            Log.d("[Broadcasts] Registered permanently on Application. clashRunning=false until CLASH_STARTED arrives.")
        } catch (e: Exception) {
            Log.w("Register global receiver: $e", e)
        }
    }

    /**
     * No-op — the receiver is intentionally kept registered for the app lifetime.
     *
     * Called by Remote when the app goes invisible, but we now ignore it so we
     * never miss ACTION_CLASH_STARTED during the invisible window.
     *
     * Per-activity UI callbacks are already gated by addObserver/removeObserver,
     * so no spurious UI updates occur while the app is in the background.
     */
    fun unregister() {
        Log.d("[Broadcasts] unregister() called but intentionally ignored — receiver stays active to track clashRunning accurately.")
    }
}