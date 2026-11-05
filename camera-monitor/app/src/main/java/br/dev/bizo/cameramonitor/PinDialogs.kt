package br.dev.bizo.cameramonitor

import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import br.dev.bizo.cameramonitor.databinding.DialogPinBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Caixas de PIN usadas pela tela principal e pelas configurações. */
object PinDialogs {

    /** Executa a ação direto se não houver PIN; senão, pede o PIN antes. */
    fun requirePin(activity: AppCompatActivity, action: () -> Unit) {
        if (!PinLock.isSet(activity)) {
            action()
            return
        }
        prompt(activity, R.string.pin_current_title, { pin -> checkPin(activity, pin) }) { action() }
    }

    /** Pede o PIN novo duas vezes e salva. */
    fun chooseNewPin(activity: AppCompatActivity, onSaved: () -> Unit) {
        val validFormat = { pin: String ->
            if (PinLock.isValidFormat(pin)) null else activity.getString(R.string.pin_invalid)
        }
        prompt(activity, R.string.pin_new_title, validFormat) { first ->
            val matches = { pin: String -> if (pin == first) null else activity.getString(R.string.pin_mismatch) }
            prompt(activity, R.string.pin_confirm_title, matches) { pin ->
                PinLock.set(activity, pin)
                onSaved()
            }
        }
    }

    private fun checkPin(activity: AppCompatActivity, pin: String): String? = when (PinLock.check(activity, pin)) {
        PinLock.Result.OK -> null
        PinLock.Result.WRONG -> activity.getString(R.string.pin_wrong)
        PinLock.Result.LOCKED ->
            activity.getString(R.string.pin_locked, ((PinLock.lockedForMs(activity) + 999) / 1000).toInt())
    }

    /** Caixa de PIN. [validate] devolve a mensagem de erro, ou null se o PIN foi aceito. */
    private fun prompt(
        activity: AppCompatActivity,
        @StringRes title: Int,
        validate: (String) -> String?,
        onAccepted: (String) -> Unit,
    ) {
        val view = DialogPinBinding.inflate(activity.layoutInflater)
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setView(view.root)
            .setPositiveButton(R.string.dialog_ok, null)
            .setNegativeButton(R.string.dialog_cancel, null)
            .create()
        val submit = {
            val pin = view.pinInput.text?.toString().orEmpty()
            val error = validate(pin)
            if (error == null) {
                dialog.dismiss()
                onAccepted(pin)
            } else {
                view.pinLayout.error = error
                view.pinInput.text?.clear()
            }
        }
        view.pinInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else {
                false
            }
        }
        // Botão OK sem fechar a caixa sozinho, para mostrar o erro e deixar tentar de novo.
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { submit() }
            view.pinInput.requestFocus()
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
    }
}
