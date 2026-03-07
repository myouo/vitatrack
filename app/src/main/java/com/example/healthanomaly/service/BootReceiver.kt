package com.example.healthanomaly.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.healthanomaly.core.PreferencesManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Broadcast receiver to restart service on device boot.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    
    @Inject lateinit var preferencesManager: PreferencesManager
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Do not restore transient collection sessions after reboot.
            preferencesManager.setCollectionEnabled(false)
        }
    }
}
