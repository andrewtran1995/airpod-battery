package com.tran.andrew.airpodbattery.airpod

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelUuid
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
    val beacons: ArrayList<Beacon> = arrayListOf()
    val beaconHits: HashMap<String, Int> = hashMapOf()

    var connected = false
        set(value) {
            Log.d(logTag, "connected set to $value")
            field = value
        }

    private val listeners: Array<ConnectionListener> by lazy { ConnectionListener.listeners(this) }
    private val scanner: BluetoothLeScanner by lazy { getAdapter().bluetoothLeScanner }

    override fun onCreate() {
        super.onCreate()

        try {
            listeners.forEach { it.register() }
        } catch (e: Exception) {
            Log.e(logTag, "exception when registering listeners", e)
        }

        if (getAdapter().isEnabled) {
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

        val filters = listOf(
            ScanFilter.Builder()
                .setManufacturerData(
                    manufacturerId,
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
        val settings = ScanSettings.Builder().apply {
            setReportDelay(5)
            setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
        }.build()

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_DENIED) {
            Log.d(this.javaClass.name, "permission denied!")
        }

        scanner.startScan(
            filters,
            settings,
            object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    if (!result.isDesired()) {
                        return
                    }

                    // Trim the list of beacons that have exceeded their TTL.
                    beacons.add(Beacon(scanResult = result))
                    beaconHits[result.device.address] =
                        beaconHits.getOrDefault(result.device.address, 0) + 1
                    beacons.removeAll(Beacon::isExpired)
                    beacons.filterNot { it.scanResult.rssi < -60 }
                        .maxByOrNull { it.scanResult.rssi }
                        ?.scanResult
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

    /**
     * Scan results:
     * * Have fake MAC addresses.
     * * Do not have UUIDs.
     * * Do not have meaningful bond states.
     *
     * Therefore, we can only rely on the size of the manufacturer specific data bytes specific to Apple.
     */
    private fun ScanResult.isDesired(): Boolean = getAppleSpecificData().size == appleBytesSize

    private fun ScanResult.getAppleSpecificData(): ByteArray =
        this.scanRecord?.getManufacturerSpecificData(manufacturerId) ?: ByteArray(0)

    private fun decodeHex(bytes: ByteArray): String = bytes
        .fold("") { s, b ->
            s + String.format("%02X", b)
        }

    private fun isDataFlipped(data: String): Boolean =
        (Integer.parseInt(data[10].toString(), 16) and 0x02) == 0

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

    internal fun getAdapter(): BluetoothAdapter =
        (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    inner class NotificationThread : Thread() {
        private val channel = 1
        private val channelId = "AirPods"
        private val manager: NotificationManagerCompat by lazy { NotificationManagerCompat.from(this@AirPodsService) }

        init {
            manager.createNotificationChannel(
                NotificationChannel(
                    channelId,
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
                if (connected) {
                    val notification =
                        NotificationCompat.Builder(this@AirPodsService, channelId).apply {
                            setSmallIcon(R.drawable.ic_launcher_background)
                            setContentTitle("AirPods Battery")
                            setContentText("l:${leftAirPod.display()}, r:${rightAirPod.display()}, case:${case.display()}")

                            setShowWhen(false)
                            setOngoing(true)
                            setAutoCancel(false)
                            setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

//                        setStyle(NotificationCompat.DecoratedCustomViewStyle())
//                        setCustomContentView(RemoteViews(packageName, R.layout.notification).apply {
//                            populate(leftAirPod, rightAirPod, case)
//                        })
//                        setCustomBigContentView(
//                            RemoteViews(
//                                packageName,
//                                R.layout.notification
//                            ).apply {
//                                populate(leftAirPod, rightAirPod, case)
//                            }
//                        )
                        }.build()
                    try {
                        manager.notify(channel, notification)
                    } catch (e: Exception) {
                        Log.e(logTag, "exception creating notification", e)
                        manager.cancel(channel)
                        manager.notify(channel, notification)
                    }
                } else {
                    manager.cancel(channel)
                }


                try {
                    sleep(10_000)
                } catch (e: Exception) {
                    Log.e(logTag, "exception thrown during sleep for notification thread", e)
                }
            }
        }
    }

    companion object {
        private val logTag: String = AirPodsService::class.simpleName!!

        private const val allBits: Byte = -1
        private const val manufacturerId: Int = 76
        private const val appleBytesSize: Int = 27
    }
}

enum class AirPodModel(
    val uuid: String
) {
    NORMAL("74ec2172-0bad-4d01-8f77-997b2be0722a"),
    PRO("2a72e02b-7b99-778f-014d-ad0b7221ec74");

    companion object {
        fun isAirPod(device: BluetoothDevice): Boolean {
            return device.uuids
                .intersect(values().map { ParcelUuid.fromString(it.uuid) })
                .isNotEmpty()
        }
    }
}

