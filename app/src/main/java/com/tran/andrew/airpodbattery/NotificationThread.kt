package com.tran.andrew.airpodbattery

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.widget.RemoteViews

class NotificationThread(private var packageName: String) : Thread() {
    private val ID = "AIRPODBATTERY"
    private val NAME = "AirPodBattery"

    fun NotificationThread(manager: NotificationManager, packageName: String) {
        manager.createNotificationChannel(NotificationChannel(ID, NAME, NotificationManager.IMPORTANCE_LOW).apply {
            enableVibration(false)
            setShowBadge(true)
        })
        this.packageName = packageName
    }

    override fun run() {
        RemoteViews(packageName, R.layout.activity_main)
        Notification()
    }
}