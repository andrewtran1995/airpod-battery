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
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.tran.andrew.airpodbattery.R
import java.util.*
import kotlin.collections.ArrayList

class AirPodsService : Service() {
    private val manufacturerId: Int = 76

    var leftAirPod: Chargeable = Chargeable.NULL
    var rightAirPod: Chargeable = Chargeable.NULL
    var case: Chargeable = Chargeable.NULL

    var airPodModel: AirPodModel? = null
    var beacons: ArrayList<Beacon> = arrayListOf()

    var connected = false

    private val listeners: Array<ConnectionListener> by lazy { ConnectionListener.listeners(this) }
    private val scanner: BluetoothLeScanner by lazy { getAdapter().bluetoothLeScanner }

    override fun onCreate() {
        super.onCreate()

        try {
            listeners.forEach { it.register() }
        } catch (e: Exception) {
        }

        if (getAdapter().isEnabled) {
            startScanner()
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
        super.onDestroy()
       listeners.forEach { it.unregister() }
    }

    internal fun startScanner() {
        val filters = listOf(
            ScanFilter.Builder()
                .setManufacturerData(
                    manufacturerId,
                    ByteArray(27).apply {
                        this[0] = 7
                        this[1] = 25
                    },
                    ByteArray(27).apply {
                        this[0] = 1
                        this[1] = 1
                    }
                )
                .build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .setMatchMode(ScanSettings.MATCH_MODE_STICKY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_DENIED) {
            Log.d(this.javaClass.name, "permission denied!")
        }

        scanner.startScan(
            filters,
            settings,
            object : ScanCallback() {
                override fun onScanFailed(errorCode: Int) {
                    Log.d(this.javaClass.name, "scan failed")
                    super.onScanFailed(errorCode)
                }
                override fun onBatchScanResults(results: MutableList<ScanResult>) {
                    super.onBatchScanResults(results)
                    results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_FIRST_MATCH, it) }
                }

                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    if (result.device?.bondState ?: 0 != BluetoothDevice.BOND_BONDED ||
                        !AirPodModel.isAirPod(result.device!!) ||
                        result.getAppleSpecificData().size != 27
                    ) {
                        return
                    }

                    // Trim the list of beacons that have exceeded their TTL.
                    beacons.add(Beacon(result = result))
                    beacons.removeAll(Beacon::isExpired)
                    beacons.filterNot { it.result.rssi < -60 }
                        .maxByOrNull { it.result.rssi }
                        ?.result
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
                            if (isManufacturerSpecificDataFlipped(it)) {
                                leftAirPod = rightAirPod.also { rightAirPod = leftAirPod }
                            }

                            airPodModel = when (it[7]) {
                                'E' -> AirPodModel.PRO
                                else -> AirPodModel.NORMAL
                            }
                        }
                }
            }
        )
    }

    private fun ScanResult.getAppleSpecificData(): ByteArray =
        this.scanRecord?.getManufacturerSpecificData(manufacturerId) ?: ByteArray(0)

    private fun decodeHex(bytes: ByteArray): String = bytes.fold("") { s, b ->
        return s + String.format("%02X", b)
    }

    private fun isManufacturerSpecificDataFlipped(data: String): Boolean =
        (Integer.parseInt(data[10].toString(), 16) and 0x02).toChar() == '0'

    internal fun stopScanner() {
        scanner.stopScan(object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
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
        private val tag = "AirPods"
        private val manager: NotificationManager
            get() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        init {
            manager.createNotificationChannel(
                NotificationChannel(
                    tag,
                    tag,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    enableVibration(false)
                    enableLights(false)
                    setShowBadge(true)
                })
        }

        override fun run() {
            while (true) {
                manager.notify(
                    1,
                    NotificationCompat.Builder(this@AirPodsService, tag).apply {
                        setShowWhen(false)
                        setOngoing(true)
                        setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                        setStyle(NotificationCompat.DecoratedCustomViewStyle())
                        setSmallIcon(R.drawable.ic_launcher_background)
                        setCustomContentView(RemoteViews(packageName, R.layout.notification).apply {
                            setTextViewText(R.id.textView, "l=$leftAirPod, r=$rightAirPod, c=$case")
                        })
                        setCustomBigContentView(RemoteViews(packageName, R.layout.notification).apply {
                            setTextViewText(R.id.textView, "l=$leftAirPod, r=$rightAirPod, c=$case")
                        })
                    }.build()
                )
                sleep(1_000)
            }
        }
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

