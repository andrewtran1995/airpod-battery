package com.tran.andrew.airpodbattery.airpod

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
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
import com.tran.andrew.airpodbattery.R
import kotlin.collections.ArrayList

class AirPodsService : Service() {
    var leftAirPod: Chargeable = Chargeable.NULL
    var rightAirPod: Chargeable = Chargeable.NULL
    var case: Chargeable = Chargeable.NULL

    var airPodModel: AirPodModel? = null
    val beacons: ArrayList<ScanResult> = arrayListOf()
    val beaconHits: HashMap<String, Int> = hashMapOf()

    var connected = false
        set(value) {
            Log.d(logTag, "connected set to $value")
            field = value
        }

    internal val adapter: BluetoothAdapter
        get() = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val listeners: Array<ConnectionListener> by lazy { ConnectionListener.listeners(this) }
    private val scanner: BluetoothLeScanner by lazy { adapter.bluetoothLeScanner }

    override fun onCreate() {
        super.onCreate()

        try {
            listeners.forEach { it.register() }
        } catch (e: Exception) {
            Log.e(logTag, "exception when registering listeners", e)
        }

        if (adapter.isEnabled) {
            startScanner()
        } else {
            Log.d(logTag, "bluetooth is not enabled")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NotificationThread().start()
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        listeners.forEach { it.unregister() }
        super.onDestroy()
    }

    internal fun startScanner() {
        Log.d(logTag, "starting scanner")

        val settings = ScanSettings.Builder().apply {
            setReportDelay(5)
            setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
        }.build()

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_DENIED) {
            Log.d(this.javaClass.name, "permission denied!")
        }

        scanner.startScan(
            scanFilters,
            settings,
            object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    if (!result.isDesired()) {
                        return
                    }

                    // Trim the list of beacons that have exceeded their TTL.
                    beacons.add(result)
                    beaconHits[result.device.address] =
                        beaconHits.getOrDefault(result.device.address, 0) + 1
                    beacons.removeAll { r -> r.isExpired() }
                    beacons.filterNot { it.rssi < -60 }
                        .maxByOrNull { it.rssi }
                        ?.getAppleSpecificData()
                        ?.let { decodeHex(it) }
                        ?.let {
                            Integer.parseInt(it[14].toString(), 16).let { chargeStatus ->
                                leftAirPod = Chargeable(
                                    Integer.parseInt(it[13].toString(), 16),
                                    (chargeStatus and 0b1) != 0
                                )
                                rightAirPod = Chargeable(
                                    Integer.parseInt(it[12].toString(), 16),
                                    (chargeStatus and 0b10) != 0
                                )
                                case = Chargeable(
                                    Integer.parseInt(it[15].toString(), 16),
                                    (chargeStatus and 0b100) != 0
                                )
                            }
                            if (isDataFlipped(it)) {
                                leftAirPod = rightAirPod.also { rightAirPod = leftAirPod }
                            }

                            airPodModel = when (it[7]) {
                                'E' -> AirPodModel.PRO
                                else -> AirPodModel.NORMAL
                            }
                        }
                    Log.d(logTag, "beaconHits: $beaconHits")
                }

                override fun onBatchScanResults(results: MutableList<ScanResult>) {
                    super.onBatchScanResults(results)
                    results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_FIRST_MATCH, it) }
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.d(this.javaClass.name, "scan failed")
                    super.onScanFailed(errorCode)
                }
            }
        )
    }

    internal fun stopScanner() {
        Log.d(logTag, "stopping scanner")
        scanner.stopScan(object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
            }
        })

        beacons.clear()

        leftAirPod = Chargeable.NULL
        rightAirPod = Chargeable.NULL
        case = Chargeable.NULL
    }

    inner class NotificationThread : Thread() {
        private val manager: NotificationManagerCompat by lazy { NotificationManagerCompat.from(this@AirPodsService) }

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
            while (true) {
                try {
                    if (connected) {
                        val notification =
                            NotificationCompat.Builder(this@AirPodsService, notificationChannelID)
                                .apply {
                                    setSmallIcon(R.drawable.ic_launcher_background)
                                    setContentTitle("AirPods Battery")
                                    setContentText("l:${leftAirPod.display()}, r:${rightAirPod.display()}, case:${case.display()}")

                                    setShowWhen(false)
                                    setOngoing(true)
                                    setAutoCancel(false)
                                    setVibrate(null)
                                    setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
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
                } catch (e: InterruptedException) {
                    Log.d(logTag, "caught InterruptedException", e)
                    manager.cancel(notificationChannel)
                } catch (e: Exception) {
                    Log.e(logTag, "unexpected exception in notification thread", e)
                }
            }
        }
    }

    companion object {
        private const val logTag: String = "AirPodsService"

        private const val notificationChannel = 1
        private const val notificationChannelID = "AirPods"

        private const val allBits: Byte = -1
        private const val manufacturerID: Int = 76
        private const val appleBytesSize: Int = 27

        private val scanFilters = listOf(
            ScanFilter.Builder()
                .setManufacturerData(
                    manufacturerID,
                    ByteArray(appleBytesSize).apply {
                        this[0] = 7
                        this[1] = 25
                    },
                    ByteArray(appleBytesSize).apply {
                        this[0] = allBits
                        this[1] = allBits
                    }
                )
                .build()
        )

        /**
         * Scan results:
         * * Have fake MAC addresses.
         * * Do not have UUIDs.
         * * Do not have meaningful bond states.
         *
         * Therefore, we can only rely on the size of the manufacturer specific data bytes specific to Apple.
         */
        private fun ScanResult.isDesired(): Boolean = getAppleSpecificData().size == appleBytesSize

        private fun ScanResult.isExpired(): Boolean =
            SystemClock.elapsedRealtimeNanos() - timestampNanos > 10_000_000_000

        private fun ScanResult.getAppleSpecificData(): ByteArray =
            this.scanRecord?.getManufacturerSpecificData(manufacturerID) ?: ByteArray(0)

        private fun decodeHex(bytes: ByteArray): String = bytes
            .fold("") { s, b ->
                s + String.format("%02X", b)
            }

        private fun isDataFlipped(data: String): Boolean =
            (Integer.parseInt(data[10].toString(), 16) and 0x02) == 0
    }
}
