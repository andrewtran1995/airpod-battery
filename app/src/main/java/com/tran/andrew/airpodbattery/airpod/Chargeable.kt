package com.tran.andrew.airpodbattery.airpod

import java.lang.Exception

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

    companion object {
        val NULL = Chargeable(chargeCode = Status.DISCONNECTED.code, connected = false)
    }
}