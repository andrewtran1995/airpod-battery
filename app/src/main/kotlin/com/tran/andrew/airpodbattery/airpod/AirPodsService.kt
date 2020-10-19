package com.tran.andrew.airpodbattery.airpod

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.tran.andrew.airpodbattery.MainActivity
import com.tran.andrew.airpodbattery.R
import kotlin.collections.ArrayList

class AirPodsService : Service() {
    var airpodsConnected = false
        set(value) {
            Log.d(logTag, "airpodsConnected set to $value")
            field = value
        }

    private val listeners: Array<ConnectionListener> by lazy { ConnectionListener.listeners(this) }

    internal val adapter: BluetoothAdapter
        get() = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val airpodScanner: AirPodScanner by lazy { AirPodScanner(adapter.bluetoothLeScanner) }

    private var notification: NotificationThread? = null

    override fun onCreate() {
        super.onCreate()

        try {
            listeners.forEach { it.register() }
        } catch (e: Exception) {
            Log.e(logTag, "exception when registering listeners", e)
        }

        if (adapter.isEnabled && adapter.bondedDevices.any { AirPodModel.isAirPod(it) && it.isConnected() }) {
            startNotification()
        } else {
            Log.d(logTag, "bluetooth is not enabled")
        }
    }

    private fun BluetoothDevice.isConnected(): Boolean = try {
        BluetoothDevice::class.java.getMethod("isConnected").invoke(this) as Boolean
    } catch (e: Exception) {
        Log.e(logTag, "exception when checking if Bluetooth device connected", e)
        throw e
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        listeners.forEach { it.unregister() }
        super.onDestroy()
    }

    private fun startScanner() {
        Log.d(logTag, "starting scanner")

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_DENIED) {
            Log.d(this.javaClass.name, "permission denied!")
        }

        airpodScanner.startScan()
    }

    private fun stopScanner() {
        Log.d(logTag, "stopping scanner")
        airpodScanner.stopScan()
    }

    internal fun startNotification() {
        startScanner()
        notification = NotificationThread().apply {
            start()
        }
    }

    /** Stops the notification view, even if the AirPods may still be connected. */
    internal fun stopNotification() {
        notification?.interrupt()
        stopScanner()
    }

    /** Disconnect should be called when the caller is confident the AirPods
     * are no longer connected, and that all views should be stopped. */
    internal fun disconnect() {
        airpodsConnected = false
        stopNotification()
    }

    inner class NotificationThread : Thread() {
        private val manager: NotificationManagerCompat by lazy { NotificationManagerCompat.from(this@AirPodsService) }
        private val builder = NotificationCompat.Builder(this@AirPodsService, notificationChannelID).apply {
            setSmallIcon(R.drawable.ic_launcher_background)
            setContentTitle("AirPods Battery")

            setShowWhen(false)
            setOngoing(true)
            setOnlyAlertOnce(true)
            setVibrate(null)
            setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

            val intent = Intent(this@AirPodsService, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            setContentIntent(PendingIntent.getActivity(this@AirPodsService, 0, intent, 0))
            setAutoCancel(false)
        }

        init {
            manager.createNotificationChannel(
                NotificationChannel(
                    notificationChannelID,
                    "AirPods Battery",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    enableVibration(false)
                    enableLights(false)
                    setShowBadge(true)
                })
        }

        override fun run() {
            try {
                while (true) {
                    if (airpodScanner.scanSuccessful) {
                        val notification = builder.apply {
                            setContentText("l:${airpodScanner.left.display()}, r:${airpodScanner.right.display()}, case:${airpodScanner.case.display()}")
                        }.build()
                        try {
                            manager.notify(notificationChannel, notification)
                        } catch (e: Exception) {
                            Log.e(logTag, "exception creating notification", e)
                            manager.cancel(notificationChannel)
                            manager.notify(notificationChannel, notification)
                        }
                    } else {
                        manager.cancel(notificationChannel)
                    }

                    sleep(2_000)
                }
            } catch (e: InterruptedException) {
                Log.d(logTag, "caught InterruptedException", e)
                manager.cancel(notificationChannel)
            } catch (e: Exception) {
                Log.e(logTag, "unexpected exception in notification thread", e)
            }
        }
    }

    companion object {
        internal const val logTag: String = "AirPodsService"

        private const val notificationChannel = 1
        private const val notificationChannelID = "AirPods"
    }
}
