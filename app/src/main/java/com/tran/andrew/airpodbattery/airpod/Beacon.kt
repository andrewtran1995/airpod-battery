package com.tran.andrew.airpodbattery.airpod

import android.bluetooth.le.ScanResult
import android.os.SystemClock

data class Beacon (
    val result: ScanResult
) {
    fun isExpired(): Boolean = SystemClock.elapsedRealtimeNanos() - result.timestampNanos > TTL

    companion object {
        const val TTL: Long = 10000000000L //  10 seconds.
    }
}