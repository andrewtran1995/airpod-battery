package com.tran.andrew.airpodbattery.airpod

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log

internal interface ConnectionListener {
    fun register()
    fun unregister()

    companion object {
        fun listeners(service: AirPodsService): Array<ConnectionListener> = arrayOf(
            Receiver.bluetoothAdapterReceiver(service),
            Receiver.bluetoothDeviceReceiver(service),
            Receiver.screenReceiver(service),
            bluetoothProfileProxy(service)
        )
    }
}

private data class Receiver(
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
        fun bluetoothAdapterReceiver(service: AirPodsService): Receiver = Receiver(
            service,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    Log.d(AirPodsService.logTag, "received intent $intent")
                    when (intent.action) {
                        BluetoothAdapter.ACTION_STATE_CHANGED -> when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                            BluetoothAdapter.STATE_ON -> if (service.airpodsConnected) service.startNotification()
                            BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> service.disconnect()
                        }
                    }
                }
            },
            IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addCategory(BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_COMPANY_ID_CATEGORY + ".76")
            }
        )

        fun bluetoothDeviceReceiver(service: AirPodsService): Receiver = Receiver(
            service,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    Log.d(AirPodsService.logTag, "received intent $intent")
                    if (intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)?.let { AirPodModel.isAirPod(it) } == false) {
                        return
                    }

                    when (intent.action) {
                        BluetoothDevice.ACTION_ACL_CONNECTED -> {
                            service.airpodsConnected = true
                            service.startNotification()
                        }
                        BluetoothDevice.ACTION_ACL_DISCONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED -> {
                            service.disconnect()
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

        fun screenReceiver(service: AirPodsService): Receiver = Receiver(
            service,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    Log.d(AirPodsService.logTag, "received intent $intent")
                    when (intent.action) {
                        Intent.ACTION_SCREEN_ON -> if (service.airpodsConnected) service.startNotification()
                        Intent.ACTION_SCREEN_OFF -> service.stopNotification()
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

private fun bluetoothProfileProxy(service: AirPodsService): ConnectionListener =
    object : ConnectionListener {
        override fun register() {
            service.adapter.getProfileProxy(
                service.applicationContext,
                object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        Log.d(AirPodsService.logTag, "onServiceConnected called")
                        val h = proxy as BluetoothHeadset
                        if (h.connectedDevices.any { AirPodModel.isAirPod(it) }) {
                            service.airpodsConnected = true
                            service.startNotification()
                        }
                    }

                    override fun onServiceDisconnected(profile: Int) {
                        Log.d(AirPodsService.logTag, "onServiceDisconnected called")
                        service.disconnect()
                    }

                },
                BluetoothProfile.HEADSET
            )
        }

        override fun unregister() {
        }
    }