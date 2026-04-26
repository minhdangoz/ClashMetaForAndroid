package com.github.kr328.clash

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.util.AutoLog
import com.github.kr328.clash.util.HealthCheck
import com.github.kr328.clash.util.ResultBroadcast
import com.github.kr328.clash.util.ResultBroadcast.finishWithResult
import com.github.kr328.clash.util.ResultBroadcast.sendResult
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID
import com.github.kr328.clash.design.R

/**
 * ExternalControlActivity
 * =======================
 * Entry point for all automation intents. Supports:
 *
 * 1. clash://install-config?url=…&name=…&type=url|file
 *    → create profile (or reuse existing by URL), commit (no UI), set active, start VPN,
 *      health-check, broadcast+finish with rich result.
 *
 * 2. ACTION_START_CLASH  – start VPN + health check + broadcast result
 * 3. ACTION_STOP_CLASH   – stop VPN + broadcast result
 * 4. ACTION_TOGGLE_CLASH – toggle VPN
 *
 * Result broadcast action : com.cmcmedia.clash.action.AUTOMATION_RESULT
 * Extras on broadcast/Activity result:
 *   success   (Boolean)
 *   event     (String)  PROFILE_CREATED | PROXY_STARTED | PROXY_STOPPED | HEALTH_CHECK
 *   message   (String)
 *   health_ok (Boolean) – only for PROXY_STARTED
 *   latency_ms(Long)    – only when health_ok = true
 */
class ExternalControlActivity : Activity(), CoroutineScope by MainScope() {

    // How long to wait for the VPN tunnel to become active before health-checking.
    private val VPN_SETTLE_MS = 2_000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)

        AutoLog.d("ExtControl", "Received action=${intent.action} data=${intent.data}")

        when (intent.action) {
            Intent.ACTION_VIEW -> handleInstallConfig()

            Intents.ACTION_START_CLASH -> {
                if (Remote.broadcasts.clashRunning) {
                    AutoLog.i("ExtControl", "Already running, skipping start")
                    Toast.makeText(this, R.string.external_control_started, Toast.LENGTH_SHORT).show()
                    sendResult(ResultBroadcast.Event.PROXY_STARTED, true, "Already running")
                    doFinish()
                } else {
                    launch { startClashAndCheck() }
                }
            }

            Intents.ACTION_STOP_CLASH -> {
                if (Remote.broadcasts.clashRunning) {
                    stopClash()
                } else {
                    AutoLog.i("ExtControl", "Already stopped")
                    Toast.makeText(this, R.string.external_control_stopped, Toast.LENGTH_SHORT).show()
                    sendResult(ResultBroadcast.Event.PROXY_STOPPED, true, "Already stopped")
                    doFinish()
                }
            }

            Intents.ACTION_TOGGLE_CLASH -> {
                if (Remote.broadcasts.clashRunning) stopClash() else launch { startClashAndCheck() }
            }

            else -> {
                AutoLog.w("ExtControl", "Unknown action: ${intent.action}")
                doFinish()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Install-config flow
    // -------------------------------------------------------------------------

    private fun handleInstallConfig() {
        val uri = intent.data ?: run {
            AutoLog.e("ExtControl", "ACTION_VIEW with no data URI")
            doFinish(); return
        }

        val url = uri.getQueryParameter("url") ?: run {
            AutoLog.e("ExtControl", "Missing 'url' query param in $uri")
            doFinish(); return
        }

        val name = uri.getQueryParameter("name") ?: getString(R.string.new_profile)
        val typeParam = uri.getQueryParameter("type")?.lowercase(Locale.getDefault())
        val profileType = when (typeParam) {
            "file" -> Profile.Type.File
            else   -> Profile.Type.Url
        }

        AutoLog.i("ExtControl", "Install-config: name=$name type=$profileType url=$url")

        launch {
            try {
                // --- Step 1: find or create profile ---
                val profile = withProfile {
                    AutoLog.d("ExtControl", "Querying all profiles to check for duplicate URL")
                    val all = queryAll()
                    val existing = all.find { it.source == url }

                    if (existing != null) {
                        AutoLog.i("ExtControl", "Reusing existing profile uuid=${existing.uuid} name=${existing.name}")
                        existing
                    } else {
                        AutoLog.i("ExtControl", "Creating new profile name=$name")
                        val uuid = create(profileType, name, url)
                        AutoLog.d("ExtControl", "Profile created uuid=$uuid, patching metadata")
                        patch(uuid, name, url, 0)
                        queryByUUID(uuid)
                    }
                } ?: run {
                    AutoLog.e("ExtControl", "Failed to create/query profile")
                    sendResult(ResultBroadcast.Event.PROFILE_CREATED, false, "Failed to create profile")
                    doFinish(); return@launch
                }

                // --- Step 2: commit (download + validate config, sets active automatically) ---
                if (profile.pending || !profile.imported) {
                    AutoLog.i("ExtControl", "Committing profile uuid=${profile.uuid}")
                    withProfile {
                        commit(profile.uuid)   // ProfileProcessor.apply() → sets activeProfile
                    }
                    AutoLog.i("ExtControl", "Commit complete, profile is now active")
                } else {
                    // Already imported – just set it active
                    AutoLog.i("ExtControl", "Profile already imported, setting active uuid=${profile.uuid}")
                    withProfile { setActive(profile) }
                }

                sendResult(ResultBroadcast.Event.PROFILE_CREATED, true, "Profile ready: ${profile.name}")

                // --- Step 3: start VPN + health check ---
                startClashAndCheck()

            } catch (e: Exception) {
                AutoLog.e("ExtControl", "install-config flow failed: ${e.message}", e)
                sendResult(ResultBroadcast.Event.PROFILE_CREATED, false, "Error: ${e.message}")
                doFinish()
            }
        }
    }

    // -------------------------------------------------------------------------
    // VPN start + health check
    // -------------------------------------------------------------------------

    private suspend fun startClashAndCheck() {
        AutoLog.i("ExtControl", "Requesting VPN start")

        val vpnPermissionIntent = startClashService()

        if (vpnPermissionIntent != null) {
            // VPN permission not yet granted – we cannot proceed headlessly.
            AutoLog.w("ExtControl", "VPN permission required, launching MainActivity")
            startActivity(
                MainActivity::class.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            sendResult(
                ResultBroadcast.Event.PROXY_STARTED,
                false,
                "VPN permission required – please grant in UI"
            )
            doFinish()
            return
        }

        // Wait for tunnel to settle before health-checking
        AutoLog.d("ExtControl", "VPN service started, waiting ${VPN_SETTLE_MS}ms for tunnel to settle")
        delay(VPN_SETTLE_MS)

        // --- Health check ---
        AutoLog.i("ExtControl", "Running health check")
        val health = HealthCheck.run()

        sendResult(
            event     = ResultBroadcast.Event.PROXY_STARTED,
            success   = health.ok,
            message   = if (health.ok) "VPN started & proxy healthy (${health.latencyMs}ms)"
                        else "VPN started but health check failed: ${health.message}",
            healthOk  = health.ok,
            latencyMs = health.latencyMs.takeIf { it >= 0 },
        )

        Toast.makeText(
            this,
            if (health.ok) getString(R.string.external_control_started)
            else "VPN started – no internet (${health.message})",
            Toast.LENGTH_LONG
        ).show()

        finishWithResult(
            event     = ResultBroadcast.Event.PROXY_STARTED,
            success   = health.ok,
            message   = health.message,
            healthOk  = health.ok,
            latencyMs = health.latencyMs.takeIf { it >= 0 },
        )
    }

    // -------------------------------------------------------------------------
    // VPN stop
    // -------------------------------------------------------------------------

    private fun stopClash() {
        AutoLog.i("ExtControl", "Stopping VPN")
        stopClashService()
        Toast.makeText(this, R.string.external_control_stopped, Toast.LENGTH_SHORT).show()
        sendResult(ResultBroadcast.Event.PROXY_STOPPED, true, "VPN stopped")
        finishWithResult(ResultBroadcast.Event.PROXY_STOPPED, true, "VPN stopped")
    }

    // -------------------------------------------------------------------------

    private fun doFinish() {
        finish()
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        super.onDestroy()
        cancel()  // cancel coroutine scope
    }
}
