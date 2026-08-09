package com.linktomac.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.linktomac.net.DeviceStatusPayload

/**
 * Reads battery level/charging state. `ACTION_BATTERY_CHANGED` is a sticky broadcast, so a
 * one-off read doesn't need a registered receiver — `registerReceiver(null, filter)` is the
 * standard idiom for reading the last sticky value directly.
 */
class BatteryStatusRepository(private val context: Context) {
    private var receiver: BroadcastReceiver? = null

    fun readCurrent(): DeviceStatusPayload? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        return toPayload(intent)
    }

    fun observe(onChange: (DeviceStatusPayload) -> Unit) {
        if (receiver != null) return
        val br = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                onChange(toPayload(intent))
            }
        }
        receiver = br
        context.registerReceiver(br, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    fun stopObserving() {
        receiver?.let { context.unregisterReceiver(it) }
        receiver = null
    }

    private fun toPayload(intent: Intent): DeviceStatusPayload {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else 0
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return DeviceStatusPayload(batteryPercent = percent, isCharging = isCharging)
    }
}
