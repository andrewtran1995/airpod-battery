package com.tran.andrew.airpodbattery

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.os.ParcelUuid
import java.lang.Exception
import kotlin.experimental.and

object AirPodService : Service() {
    var leftAirPod: Chargeable? = null
    var rightAirPod: Chargeable? = null
    var case: Chargeable? = null
    var airpodModel: AirPodModel? = null

    var connected = false

    fun getDevice(context: Context) {
        BluetoothAdapter.getDefaultAdapter().getProfileProxy(
            context,
            object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                    val h = proxy as BluetoothHeadset
                    connected = h.connectedDevices.any { AirPodModel.isAirPod(it) }
                }

                override fun onServiceDisconnected(profile: Int) {
                    connected = false
                }

            },
            BluetoothProfile.HEADSET
        )
    }

    fun startScan() {
        BluetoothAdapter.getDefaultAdapter().bluetoothLeScanner.startScan(object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                if (result?.device?.bondState ?: 0 != BluetoothDevice.BOND_BONDED ||
                    !AirPodModel.isAirPod(result?.device!!)
                ) {
                    return
                }

                val data = result.scanRecord
                    ?.getManufacturerSpecificData(76)
                    ?: ByteArray(0)
                if (data.size != 27) {
                    return
                }

                decodeHex(data).let {
                    Integer.parseInt(it[14].toString(), 16).let { isChargingCode ->
                        rightAirPod = Chargeable(
                            Integer.parseInt(it[12].toString(), 16),
                            (isChargingCode and 0b10) != 0
                        )
                        leftAirPod = Chargeable(
                            Integer.parseInt(it[13].toString(), 16),
                            (isChargingCode and 0b1) != 0
                        )
                        case = Chargeable(
                            Integer.parseInt(it[15].toString(), 16),
                            (isChargingCode and 0b100) != 0
                        )
                    }
                    if (isManufacturerSpecificDataFlipped(it)) {
                        leftAirPod = rightAirPod.also { rightAirPod = leftAirPod }
                    }

                    airpodModel = when (it[7]) {
                        'E' -> AirPodModel.PRO
                        else -> AirPodModel.NORMAL
                    }
                }
            }
        })
    }

    private val hexCharSet =
        charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F')

    fun decodeHex(bArr: ByteArray): String {
        val ret = CharArray(bArr.size * 2)

        for (i in bArr.indices) {
            val b: Int = (bArr[i] and 0xFF.toByte()).toInt()
            ret[i * 2] = hexCharSet[b ushr 4]
            ret[i * 2 + 1] = hexCharSet[b and 0x0F]
        }

        return String(ret)
    }

    fun isManufacturerSpecificDataFlipped(data: String): Boolean =
        (Integer.parseInt(data[10].toString(), 16) + 0x10).toString()[3] == '0'

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()

        val intentFilter = IntentFilter().apply {
            listOf(
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_ACL_DISCONNECTED
            ).forEach { addAction(it) }
        }

//        registerReceiver( )
    }
}

enum class AirPodModel(
    val uuid: String,
    val type: String
) {
    NORMAL("74ec2172-0bad-4d01-8f77-997b2be0722a", "airpods12"),
    PRO("2a72e02b-7b99-778f-014d-ad0b7221ec74", "airpodspro");

    companion object {
        fun isAirPod(device: BluetoothDevice): Boolean {
            return device.uuids
                .intersect(values().map { ParcelUuid.fromString(it.uuid) })
                .isNotEmpty()
        }
    }
}

// (0-10 batt; 15=disconnected)
data class Chargeable(
    val chargeCode: Int,
    val connected: Boolean
) {
    enum class Status(val code: Int) {
        DISCONNECTED(15)
    }

    fun charge(): Float {
        if (chargeCode > 10) {
            throw Exception("invalid charge code: $chargeCode")
        }
        return (chargeCode / 10).toFloat()
    }
}