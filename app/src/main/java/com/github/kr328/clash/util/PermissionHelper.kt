package com.github.kr328.clash.util

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat

class PermissionHelper(private val activity: Activity) {

    private val TAG = "PermissionHelper"
    private val requiredPermissions = mutableListOf(
        Manifest.permission.ACCESS_NETWORK_STATE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
        Manifest.permission.READ_EXTERNAL_STORAGE,
    ).apply {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            add(Manifest.permission.MANAGE_EXTERNAL_STORAGE)
        }

        // Add notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Check and request permissions
    fun checkAndRequestPermissions(permissionLauncher: ActivityResultLauncher<Array<String>>?): Boolean {
        val permissionsToRequest = mutableListOf<String>()

        // Check which permissions need to be requested
        for (permission in requiredPermissions) {
            if (ContextCompat.checkSelfPermission(activity, permission) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(permission)
            }
        }

        return if (permissionsToRequest.isEmpty()) {
            // All permissions already granted
            android.util.Log.d(TAG, "--> All permissions already granted")
            true
        } else {
            // Request permissions
            android.util.Log.d(TAG, "--> Request permissions")

            permissionLauncher?.launch(permissionsToRequest.toTypedArray())
            false
        }
    }
}