package com.example.healthanomaly.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.healthanomaly.R
import com.example.healthanomaly.domain.model.AnomalyEvent
import com.example.healthanomaly.presentation.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Helper for creating and managing notifications.
 */
@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val CHANNEL_ID_ALERTS = "health_alerts"
        const val CHANNEL_ID_FOREGROUND = "foreground_service"
        const val FOREGROUND_NOTIFICATION_ID = 1001
    }
    
    init {
        createNotificationChannels()
    }
    
    /**
     * Create notification channels for Android O+.
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            
            // Alert channel (high importance)
            val alertChannel = NotificationChannel(
                CHANNEL_ID_ALERTS,
                context.getString(R.string.notification_channel_alerts),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notification_channel_alerts)
                enableVibration(true)
                enableLights(true)
            }
            
            // Foreground service channel
            val foregroundChannel = NotificationChannel(
                CHANNEL_ID_FOREGROUND,
                context.getString(R.string.notification_channel_foreground),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.notification_channel_foreground)
            }
            
            notificationManager.createNotificationChannel(alertChannel)
            notificationManager.createNotificationChannel(foregroundChannel)
        }
    }
    
    /**
     * Show anomaly alert notification.
     */
    fun showAnomalyAlert(event: AnomalyEvent) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("event_id", event.id)
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context,
            event.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${context.getString(R.string.notification_channel_alerts)}: ${formatAnomalyType(event.type)}")
            .setContentText(event.details)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setVibrate(longArrayOf(0, 500, 200, 500))
            .build()
        
        try {
            NotificationManagerCompat.from(context)
                .notify(event.id.toInt(), notification)
        } catch (e: SecurityException) {
            // Notification permission not granted
        }
    }
    
    /**
     * Show foreground service notification.
     */
    fun showForegroundNotification(title: String, content: String): android.app.Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val stopIntent = Intent().apply {
            action = "com.example.healthanomaly.STOP_SERVICE"
        }
        val stopPendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_FOREGROUND)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_stop, context.getString(R.string.stop_service), stopPendingIntent)
            .build()
        
        try {
            NotificationManagerCompat.from(context)
                .notify(FOREGROUND_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Notification permission not granted
        }
        
        return notification
    }
    
    private fun formatAnomalyType(type: com.example.healthanomaly.domain.model.AnomalyType): String {
        return type.name.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }
    }
    
    fun cancelForegroundNotification() {
        try {
            NotificationManagerCompat.from(context).cancel(FOREGROUND_NOTIFICATION_ID)
        } catch (e: SecurityException) {
            // Notification permission not granted
        }
    }
}
