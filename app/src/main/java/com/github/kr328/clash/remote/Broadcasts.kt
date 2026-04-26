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
import java.util.*

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

                // ----------------------------------------------------------------
                // Automation result – log for debugging, no UI reaction needed
                // (UI is already driven by CLASH_STARTED / CLASH_STOPPED above)
                // ----------------------------------------------------------------
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
                addAction(ResultBroadcast.ACTION_RESULT)   // ← new
            })

            clashRunning = StatusClient(context).currentProfile() != null
            Log.d("[Broadcasts] Registered. clashRunning=$clashRunning")
            registered = true
        } catch (e: Exception) {
            Log.w("Register global receiver: $e", e)
        }
    }

    fun unregister() {
        if (!registered) return

        try {
            context.unregisterReceiver(broadcastReceiver)
            clashRunning = false
            registered = false
            Log.d("[Broadcasts] Unregistered")
        } catch (e: Exception) {
            Log.w("Unregister global receiver: $e", e)
        }
    }
}
