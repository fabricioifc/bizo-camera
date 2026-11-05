package br.dev.bizo.cameramonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Volta a transmitir depois que o celular reinicia ou o app é atualizado,
 * se a câmera estava ligada antes.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val relay = RelayClient(context)
        if (!StreamService.shouldRun(context) || !relay.isSignedIn || !relay.isRegistered) return
        ContextCompat.startForegroundService(context, Intent(context, StreamService::class.java))
    }
}
