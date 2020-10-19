package com.tran.andrew.airpodbattery.airpod

import android.widget.RemoteViews
import android.widget.RemoteViews.RemoteView
import com.tran.andrew.airpodbattery.R
import java.lang.Exception

// (0-10 batt; 15=disconnected)
data class Chargeable(
    val chargeCode: Int,
    val connected: Boolean
) {
    enum class Status(val code: Int) {
        DISCONNECTED(15)
    }

    fun display(): String = "$chargeCode, $connected"

    companion object {
        val NULL = Chargeable(chargeCode = Status.DISCONNECTED.code, connected = false)
    }
}

internal fun RemoteViews.populate(left: Chargeable, right: Chargeable, case: Chargeable) {
    setTextViewText(R.id.textView, "l:${left.display()}, r:${right.display()}, case:${case.display()}")
}