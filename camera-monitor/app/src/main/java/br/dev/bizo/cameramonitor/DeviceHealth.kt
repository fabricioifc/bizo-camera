package br.dev.bizo.cameramonitor

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import org.json.JSONObject

/** Bateria e temperatura do celular da câmera, enviadas ao servidor para aparecer na web. */
data class DeviceHealth(
    val batteryPercent: Int?,
    val charging: Boolean,
    val temperatureCelsius: Double?,
) {
    /** Mensagem "health" para o servidor. */
    fun toMessage(throttled: Boolean): JSONObject {
        val message = JSONObject()
            .put("type", "health")
            .put("charging", charging)
            .put("throttled", throttled)
        if (batteryPercent != null) message.put("battery", batteryPercent)
        if (temperatureCelsius != null) message.put("temperature", temperatureCelsius)
        return message
    }

    companion object {
        /** Lê o último aviso de bateria do sistema (não registra nenhum receptor de verdade). */
        fun read(context: Context): DeviceHealth? {
            val battery = ContextCompat.registerReceiver(
                context, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
            ) ?: return null
            val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            // A temperatura vem em décimos de grau Celsius.
            val temperature = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            return DeviceHealth(
                batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else null,
                charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL,
                temperatureCelsius = if (temperature != Int.MIN_VALUE) temperature / 10.0 else null,
            )
        }
    }
}
