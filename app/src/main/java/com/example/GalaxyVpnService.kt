package com.example

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat

class GalaxyVpnService : VpnService() {

    private var isRunning = false
    private var vpnInterface: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            disconnect()
            return START_NOT_STICKY
        }
        
        val serverName = intent?.getStringExtra(EXTRA_SERVER_NAME) ?: "Galaxy Node"
        connect(serverName)
        return START_STICKY
    }

    private fun connect(serverName: String) {
        if (isRunning) return
        isRunning = true

        try {
            val builder = Builder()
            builder.setSession("Galaxy Tunnel")
                .addAddress("10.8.0.2", 32)
                .addRoute("10.8.0.0", 24)
                .setMtu(1500)
            
            vpnInterface = builder.establish()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        createNotificationChannel()
        val stopIntent = Intent(this, GalaxyVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStopIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Galaxy Tunnel Active")
            .setContentText("Connected to $serverName")
            .setSmallIcon(android.R.drawable.stat_sys_phone_call) // compatible built-in identifier
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", pendingStopIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun disconnect() {
        isRunning = false
        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Galaxy Tunnel VPN Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    companion object {
        const val CHANNEL_ID = "GalaxyVpnChannel"
        const val NOTIFICATION_ID = 7771
        
        const val ACTION_STOP = "com.example.ACTION_STOP"
        const val EXTRA_SERVER_NAME = "EXTRA_SERVER_NAME"
    }
}
