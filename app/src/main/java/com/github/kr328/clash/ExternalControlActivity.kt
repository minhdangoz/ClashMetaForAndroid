package com.github.kr328.clash

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.service.StatusProvider.Companion.currentProfile
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.util.*
import com.github.kr328.clash.design.R

class ExternalControlActivity : Activity(), CoroutineScope by MainScope() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)

        when(intent.action) {
            Intent.ACTION_VIEW -> {
                val uri = intent.data ?: return finish()
                val url = uri.getQueryParameter("url") ?: return finish()

                launch {
                    val profile = withProfile {
                        val type = when (uri.getQueryParameter("type")?.lowercase(Locale.getDefault())) {
                            "url" -> Profile.Type.Url
                            "file" -> Profile.Type.File
                            else -> Profile.Type.Url
                        }
                        val name = uri.getQueryParameter("name") ?: getString(R.string.new_profile)

                        val all = queryAll()
                        val existing = all.find { it.source == url }
                        
                        if (existing != null) {
                            existing
                        } else {
                            val uuid = create(type, name).also {
                                patch(it, name, url, 0)
                            }
                            queryByUUID(uuid)
                        }
                    }
                    
                    if (profile != null) {
                        withProfile {
                            if (profile.pending) {
                                commit(profile.uuid)
                            } else {
                                setActive(profile)
                            }
                        }
                    }
                    
                    startClash()
                    
                    finish()
                }
                return
            }

            Intents.ACTION_TOGGLE_CLASH -> if(Remote.broadcasts.clashRunning) {
                stopClash()
            }
            else {
                startClash()
            }

            Intents.ACTION_START_CLASH -> if(!Remote.broadcasts.clashRunning) {
                startClash()
            }
            else {
                Toast.makeText(this, R.string.external_control_started, Toast.LENGTH_LONG).show()
            }

            Intents.ACTION_STOP_CLASH -> if(Remote.broadcasts.clashRunning) {
                stopClash()
            }
            else {
                Toast.makeText(this, R.string.external_control_stopped, Toast.LENGTH_LONG).show()
            }
        }
        return finish()
    }

    private fun startClash() {
        if (currentProfile == null) {
            val vpnRequest = startClashService()
            if (vpnRequest != null) {
                // If VPN permission is needed, we have to show some UI. 
                startActivity(MainActivity::class.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Toast.makeText(this, R.string.unable_to_start_vpn, Toast.LENGTH_LONG).show()
                return
            }
            Toast.makeText(this, R.string.external_control_started, Toast.LENGTH_LONG).show()
        } else {
             // Already running, maybe we need to restart to apply new profile?
             // ConfigurationModule handles profile change automatically.
             Toast.makeText(this, getString(R.string.external_control_started), Toast.LENGTH_LONG).show()
        }
    }

    private fun stopClash() {
        stopClashService()
        Toast.makeText(this, R.string.external_control_stopped, Toast.LENGTH_LONG).show()
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}