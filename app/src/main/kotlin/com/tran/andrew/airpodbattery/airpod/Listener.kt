package com.tran.andrew.airpodbattery.airpod

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

internal interface ConnectionListener {
    fun register()
    fun unregister()

    companion object {
        fun listeners(service: AirPodsService) : Array<ConnectionListener> = arrayOf(
            Receiver.bluetoothReceiver(service),
            Receiver.screenReceiver(service),
            bluetoothProfileProxy(service)
        )
    }
}

private data class Receiver (
    private val service: AirPodsService,
    private val broadcastReceiver: BroadcastReceiver,
    private val intentFilter: IntentFilter
) : ConnectionListener {
    override fun register() {
        service.registerReceiver(broadcastReceiver, intentFilter)
    }

    override fun unregister() {
        service.unregisterReceiver(broadcastReceiver)
    }

    companion object {
        fun bluetoothReceiver(service: AirPodsService) : Receiver = Receiver(
            service,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action.equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {
                        when (intent.getIntExtra(
                            BluetoothAdapter.EXTRA_STATE,
                            BluetoothAdapter.ERROR
                        )) {
                            BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> {
                                service.connected = false
                                service.stopScanner()
                            }
                            BluetoothAdapter.STATE_ON -> {
                                service.startScanner()
                            }
                        }
                    }

                    intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        ?.let {
                            if (intent.action?.isEmpty() != false || !AirPodModel.isAirPod(it)) {
                                return
                            }

                            when (intent.action) {
                                BluetoothDevice.ACTION_ACL_CONNECTED -> service.connected = true
                                BluetoothDevice.ACTION_ACL_DISCONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED -> {
                                    service.connected = false
                                    service.beacons.clear()
                                }
                            }
                        }
                }
            },
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addCategory(BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_COMPANY_ID_CATEGORY + ".76")
            }
        )

        fun screenReceiver(service: AirPodsService) : Receiver = Receiver(
            service,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.action) {
                        Intent.ACTION_SCREEN_OFF -> service.stopScanner()
                        Intent.ACTION_SCREEN_ON -> if (service.getAdapter().isEnabled) service.startScanner()
                    }
                }
            },
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
        )
    }
}

private fun bluetoothProfileProxy(service: AirPodsService) : ConnectionListener = object : ConnectionListener {
    override fun register() {
        service.getAdapter().getProfileProxy(
            service.applicationContext,
            object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    val h = proxy as BluetoothHeadset
                    service.connected = h.connectedDevices.any { AirPodModel.isAirPod(it) }
                }

                override fun onServiceDisconnected(profile: Int) {
                    service.connected = false
                }

            },
            BluetoothProfile.HEADSET
        )
    }

    override fun unregister() {
    }
}