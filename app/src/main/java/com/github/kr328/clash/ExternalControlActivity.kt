package com.github.kr328.clash

import android.app.Activity
import android.content.Intent
import android.net.Uri
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
import com.github.kr328.clash.design.R

/**
 * ExternalControlActivity
 * =======================
 * Entry point for all automation intents. Supports:
 *
 * 1. clash://install-config?url=…&name=…&type=url|file
 *    → create/reuse profile, commit, set active, start VPN, health-check, broadcast result
 *
 *    File type URL formats accepted:
 *      - Raw path  : /storage/emulated/0/config.yaml
 *                    → normalized to file:///storage/emulated/0/config.yaml
 *      - file URI  : file:///storage/emulated/0/config.yaml
 *      - content   : content://com.example.provider/external_files/config.yaml
 *                    → persistable URI permission is taken before handing to service
 *
 * 2. ACTION_START_CLASH        – start VPN + health check + broadcast result
 * 3. ACTION_STOP_CLASH         – stop VPN + broadcast result
 * 4. ACTION_TOGGLE_CLASH       – toggle VPN
 * 5. ACTION_UPDATE_PROFILE     – re-fetch active profile config, restart VPN, health check
 *
 * Result broadcast: com.cmcmedia.clash.action.AUTOMATION_RESULT
 * Extras: success(bool), event(string), message(string), health_ok(bool), latency_ms(long)
 */
class ExternalControlActivity : Activity(), CoroutineScope by MainScope() {

    private val VPN_SETTLE_MS = 2_000L

    companion object {
        const val ACTION_UPDATE_PROFILE = "com.cmcmedia.clash.action.UPDATE_PROFILE"

        /**
         * Normalize any file path/URI to a form that passes ProfileProcessor
         * enforceFieldValid() and that importLocalFile() can open.
         *
         * Rules:
         *  - Raw absolute path (/storage/…)  → file:///storage/…
         *  - file:// URI                      → kept as-is
         *  - content:// URI                   → kept as-is
         *  - http/https                       → kept as-is (URL profiles)
         */
        fun normalizeFileUrl(raw: String): String {
            if (raw.startsWith("content://") ||
                raw.startsWith("file://") ||
                raw.startsWith("http://") ||
                raw.startsWith("https://")) {
                return raw
            }
            // Raw absolute path — wrap in file:// scheme
            if (raw.startsWith("/")) {
                return "file://$raw"
            }
            return raw
        }
    }

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

            ACTION_UPDATE_PROFILE -> launch { handleUpdateProfile() }

            else -> {
                AutoLog.w("ExtControl", "Unknown action: ${intent.action}")
                doFinish()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Update active profile flow
    // -------------------------------------------------------------------------

    private suspend fun handleUpdateProfile() {
        AutoLog.i("ExtControl", "UPDATE_PROFILE requested")

        try {
            // --- Step 1: get active profile ---
            val active = withProfile { queryActive() }

            if (active == null) {
                AutoLog.e("ExtControl", "No active profile found")
                sendResult(ResultBroadcast.Event.PROFILE_UPDATED, false, "No active profile")
                finishWithResult(ResultBroadcast.Event.PROFILE_UPDATED, false, "No active profile")
                return
            }

            AutoLog.i("ExtControl", "Updating active profile: name=${active.name} uuid=${active.uuid} source=${active.source}")

            // --- Step 2: stop VPN if running so new config can be applied cleanly ---
            val wasRunning = Remote.broadcasts.clashRunning
            if (wasRunning) {
                AutoLog.d("ExtControl", "Stopping VPN before profile update")
                stopClashService()
                delay(1_000L)
            }

            // --- Step 3: re-fetch config for the imported profile ---
            // commit() only works on pending profiles (PendingDao).
            // An active imported profile must use update() → ProfileProcessor.update()
            // which re-downloads and validates the config from source URL.
            AutoLog.d("ExtControl", "Updating imported profile uuid=${active.uuid}")
            withProfile {
                update(active.uuid)
            }
            AutoLog.i("ExtControl", "Profile update complete")

            sendResult(ResultBroadcast.Event.PROFILE_UPDATED, true, "Profile updated: ${active.name}")

            // --- Step 4: restart VPN + health check ---
            if (wasRunning) {
                AutoLog.d("ExtControl", "Restarting VPN after profile update")
                startClashAndCheck(event = ResultBroadcast.Event.PROFILE_UPDATED)
            } else {
                AutoLog.d("ExtControl", "VPN was not running before update, skipping restart")
                finishWithResult(ResultBroadcast.Event.PROFILE_UPDATED, true, "Profile updated: ${active.name}")
            }

        } catch (e: Exception) {
            AutoLog.e("ExtControl", "Profile update failed: ${e.message}", e)
            sendResult(ResultBroadcast.Event.PROFILE_UPDATED, false, "Update failed: ${e.message}")
            finishWithResult(ResultBroadcast.Event.PROFILE_UPDATED, false, "Update failed: ${e.message}")
        }
    }

    // -------------------------------------------------------------------------
    // Install-config flow
    // -------------------------------------------------------------------------

    private fun handleInstallConfig() {
        val intentUri = intent.data ?: run {
            AutoLog.e("ExtControl", "ACTION_VIEW with no data URI")
            doFinish(); return
        }

        val rawUrl = intentUri.getQueryParameter("url") ?: run {
            AutoLog.e("ExtControl", "Missing 'url' query param in $intentUri")
            doFinish(); return
        }

        val name = intentUri.getQueryParameter("name") ?: getString(R.string.new_profile)
        val typeParam = intentUri.getQueryParameter("type")?.lowercase(Locale.getDefault())
        val profileType = when (typeParam) {
            "file" -> Profile.Type.File
            else   -> Profile.Type.Url
        }

        // Normalize the URL before any further processing.
        // Raw paths → file:// so enforceFieldValid() accepts them.
        val url = normalizeFileUrl(rawUrl)

        AutoLog.i("ExtControl", "Install-config: name=$name type=$profileType rawUrl=$rawUrl normalizedUrl=$url")

        // For content:// URIs: take persistable read permission NOW while this
        // activity still holds the transient grant from the calling app.
        // RemoteService runs in a separate process and would otherwise have no
        // access to the URI when ProfileProcessor.importLocalFile() runs later.
        if (url.startsWith("content://")) {
            try {
                val contentUri = Uri.parse(url)
                contentResolver.takePersistableUriPermission(
                    contentUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                AutoLog.d("ExtControl", "Took persistable read permission for $contentUri")
            } catch (e: Exception) {
                // Non-fatal: the caller may not have granted persistable permission.
                // ProfileProcessor.importLocalFile() will still try; log for debug.
                AutoLog.w("ExtControl", "Could not take persistable URI permission: ${e.message}")
            }
        }

        launch {
            try {
                // --- Step 1: find or create profile ---
                val profile = withProfile {
                    AutoLog.d("ExtControl", "Querying all profiles to check for duplicate url")
                    val all = queryAll()
                    val existing = all.find { it.source == url }

                    if (existing != null) {
                        AutoLog.i("ExtControl", "Reusing existing profile uuid=${existing.uuid} name=${existing.name}")
                        existing
                    } else {
                        AutoLog.i("ExtControl", "Creating new profile name=$name type=$profileType")
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

                // --- Step 2: commit (validates config, sets active automatically) ---
                if (profile.pending || !profile.imported) {
                    AutoLog.i("ExtControl", "Committing profile uuid=${profile.uuid}")
                    withProfile { commit(profile.uuid) }
                    AutoLog.i("ExtControl", "Commit complete, profile is now active")
                } else {
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

    private suspend fun startClashAndCheck(event: String = ResultBroadcast.Event.PROXY_STARTED) {
        AutoLog.i("ExtControl", "Requesting VPN start")

        val vpnPermissionIntent = startClashService()

        if (vpnPermissionIntent != null) {
            AutoLog.w("ExtControl", "VPN permission required, launching MainActivity")
            startActivity(MainActivity::class.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            sendResult(event, false, "VPN permission required – please grant in UI")
            doFinish()
            return
        }

        AutoLog.d("ExtControl", "VPN service started, waiting ${VPN_SETTLE_MS}ms for tunnel to settle")
        delay(VPN_SETTLE_MS)

        AutoLog.i("ExtControl", "Running health check")
        val health = HealthCheck.run()

        val message = if (health.ok) "VPN started & proxy healthy (${health.latencyMs}ms)"
        else "VPN started but health check failed: ${health.message}"

        sendResult(
            event     = event,
            success   = health.ok,
            message   = message,
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
            event     = event,
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

    private fun doFinish() = finish()

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        super.onDestroy()
        cancel()
    }
}