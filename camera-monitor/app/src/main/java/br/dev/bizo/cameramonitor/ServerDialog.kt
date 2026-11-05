package br.dev.bizo.cameramonitor

import android.content.Intent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import br.dev.bizo.cameramonitor.databinding.DialogServerBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executor

/** Caixa para trocar o endereço do servidor, usada no login e nas configurações. */
object ServerDialog {

    /**
     * Confere se o novo endereço responde antes de salvar. Se a câmera estiver ligada,
     * reconecta no endereço novo. [onSaved] é chamado só quando o endereço muda.
     */
    fun show(activity: AppCompatActivity, relay: RelayClient, io: Executor, onSaved: () -> Unit) {
        val view = DialogServerBinding.inflate(activity.layoutInflater)
        view.serverInput.setText(ServerSettings.url(activity))
        view.serverLayout.helperText = activity.getString(R.string.server_helper, displayName(ServerSettings.defaultUrl))
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.server_dialog_title)
            .setView(view.root)
            .setPositiveButton(R.string.dialog_save, null)
            .setNeutralButton(R.string.server_default, null)
            .setNegativeButton(R.string.dialog_cancel, null)
            .create()

        fun setChecking(checking: Boolean) {
            view.serverProgress.visibility = if (checking) View.VISIBLE else View.GONE
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = !checking
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = !checking
        }

        fun save(input: String) {
            // Builds de teste aceitam http:// (servidor na rede local); o release exige https://.
            val url = ServerSettings.normalize(input, allowHttp = BuildConfig.DEBUG)
            if (url == null) {
                view.serverLayout.error = activity.getString(R.string.server_invalid)
                return
            }
            view.serverLayout.error = null
            setChecking(true)
            io.execute {
                val reachable = relay.checkServer(url)
                activity.runOnUiThread {
                    setChecking(false)
                    if (!reachable) {
                        view.serverLayout.error = activity.getString(R.string.server_unreachable)
                        return@runOnUiThread
                    }
                    val changed = url != ServerSettings.url(activity)
                    ServerSettings.setUrl(activity, url)
                    if (changed) {
                        if (StreamService.isRunning) {
                            activity.startService(Intent(activity, StreamService::class.java).setAction(StreamService.ACTION_STOP))
                        }
                        relay.resetForServerChange()
                    }
                    dialog.dismiss()
                    if (!changed) return@runOnUiThread
                    onSaved()
                }
            }
        }

        view.serverInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                save(view.serverInput.text?.toString().orEmpty())
                true
            } else {
                false
            }
        }
        // Botões sem fechar a caixa sozinhos, para mostrar o erro e deixar corrigir.
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                save(view.serverInput.text?.toString().orEmpty())
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                view.serverInput.setText(ServerSettings.defaultUrl)
                save(ServerSettings.defaultUrl)
            }
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
    }

    /** Endereço sem o `https://`, para mostrar na tela. */
    fun displayName(url: String): String = url.substringAfter("://")
}
