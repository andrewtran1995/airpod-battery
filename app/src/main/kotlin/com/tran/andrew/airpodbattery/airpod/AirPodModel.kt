package com.tran.andrew.airpodbattery.airpod

import android.bluetooth.BluetoothDevice
import android.os.ParcelUuid

enum class AirPodModel(
    val uuid: String
) {
    NORMAL("74ec2172-0bad-4d01-8f77-997b2be0722a"),
    PRO("2a72e02b-7b99-778f-014d-ad0b7221ec74");

    companion object {
        private val UUIDs = values().map { ParcelUuid.fromString(it.uuid) }

        fun isAirPod(device: BluetoothDevice): Boolean =
            device.uuids
                .intersect(UUIDs)
                .isNotEmpty()
    }
}
