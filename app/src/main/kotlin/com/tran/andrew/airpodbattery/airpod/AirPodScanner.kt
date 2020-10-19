package com.tran.andrew.airpodbattery.airpod

import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.SystemClock
import android.util.Log

internal class AirPodScanner(private val scanner: BluetoothLeScanner){
    internal var left = Chargeable.NULL
    internal var right = Chargeable.NULL
    internal var case = Chargeable.NULL
    internal var model: AirPodModel? = null

    private val beacons: ArrayList<ScanResult> = arrayListOf()
    private val beaconHits: HashMap<String, Int> = hashMapOf()

    internal var scanSuccessful = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!result.isDesired()) {
                return
            }

            // Trim the list of beacons that have exceeded their TTL.
            beacons.add(result)
            beaconHits[result.device.address] = beaconHits.getOrDefault(result.device.address, 0) + 1
            beacons.removeAll { it.isExpired() }
            beacons.filterNot { it.rssi < -60 }
                .maxByOrNull { it.rssi }
                ?.getAppleSpecificData()
                ?.let { decodeBytesAsHexData(it) }
                ?.let { data ->
                    Integer.parseInt(data[14].toString(), 16).let { chargeStatus ->
                        left = Chargeable(
                            Integer.parseInt(data[13].toString(), 16),
                            (chargeStatus and 0b1) != 0
                        )
                        right = Chargeable(
                            Integer.parseInt(data[12].toString(), 16),
                            (chargeStatus and 0b10) != 0
                        )
                        case = Chargeable(
                            Integer.parseInt(data[15].toString(), 16),
                            (chargeStatus and 0b100) != 0
                        )
                    }
                    if (isDataFlipped(data)) {
                        left = right.also { right = left }
                    }

                    model = when (data[7]) {
                        'E' -> AirPodModel.PRO
                        else -> AirPodModel.NORMAL
                    }

                    scanSuccessful = true
                }
            Log.d(AirPodsService.logTag, "beaconHits: $beaconHits")
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            super.onBatchScanResults(results)
        }
    }

    internal fun startScan() {
        scanner.startScan(scanFilters, scanSettings, scanCallback)
    }

    internal fun stopScan() {
        scanner.stopScan(scanCallback)
        scanner.flushPendingScanResults(scanCallback)

        beacons.clear()
        left = Chargeable.NULL
        right = Chargeable.NULL
        case = Chargeable.NULL
        model = null

        scanSuccessful = false
    }

    companion object {
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

        private val scanSettings = ScanSettings.Builder().apply {
            setReportDelay(1)
            setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
        }.build()

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

        private fun decodeBytesAsHexData(bytes: ByteArray): String =
            bytes.fold("") { s, b ->
                s + String.format("%02X", b)
            }

        private fun isDataFlipped(data: String): Boolean =
            (Integer.parseInt(data[10].toString(), 16) and 0x02) == 0
    }
}