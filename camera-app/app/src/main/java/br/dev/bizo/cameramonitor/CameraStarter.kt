package br.dev.bizo.cameramonitor

import android.app.Activity
import android.content.Intent
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor

/** Liga e desliga a transmissão: registra o aparelho na conta e sobe o serviço da câmera. */
object CameraStarter {

    /**
     * Registra o aparelho (para o serviço já conectar) e inicia o [StreamService].
     * [onDone] recebe null se deu certo, ou a mensagem de erro.
     */
    fun start(activity: Activity, relay: RelayClient, io: Executor, onDone: (error: String?) -> Unit) {
        io.execute {
            val registered = relay.registerDevice()
            activity.runOnUiThread {
                if (registered) {
                    ContextCompat.startForegroundService(activity, Intent(activity, StreamService::class.java))
                    onDone(null)
                } else {
                    val detail = relay.lastError?.takeIf { BuildConfig.DEBUG }?.let { "\n($it)" }.orEmpty()
                    onDone(activity.getString(R.string.register_error) + detail)
                }
            }
        }
    }

    fun stop(activity: Activity) {
        activity.startService(Intent(activity, StreamService::class.java).setAction(StreamService.ACTION_STOP))
    }
}
