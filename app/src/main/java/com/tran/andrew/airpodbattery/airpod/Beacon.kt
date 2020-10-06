package com.tran.andrew.airpodbattery.airpod

import android.bluetooth.le.ScanResult
import android.os.SystemClock

data class Beacon (
    val scanResult: ScanResult
) {
    fun isExpired(): Boolean = SystemClock.elapsedRealtimeNanos() - scanResult.timestampNanos > TTL

    companion object {
        const val TTL: Long = 10_000_000_000 //  10 seconds.
    }
}