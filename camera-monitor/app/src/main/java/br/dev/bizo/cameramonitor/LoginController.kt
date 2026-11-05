package br.dev.bizo.cameramonitor

import android.graphics.BitmapFactory
import android.util.Base64
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.lifecycle.lifecycleScope
import br.dev.bizo.cameramonitor.databinding.ViewLoginBinding
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.launch
import java.util.concurrent.Executor

/**
 * Fluxo de login do cartão da tela principal: Google (seletor de conta do próprio app),
 * código 2FA, configuração do 2FA e códigos de recuperação.
 *
 * Ao terminar, registra o aparelho na conta e chama [onSignedIn] com o resultado do registro.
 */
class LoginController(
    private val activity: AppCompatActivity,
    private val views: ViewLoginBinding,
    private val relay: RelayClient,
    private val io: Executor,
    private val onSignedIn: (registered: Boolean) -> Unit,
) {
    // Etapas do login: entrar com o Google, código 2FA, configurar 2FA e guardar os códigos de recuperação.
    private enum class Step { SIGN_IN, CODE, SETUP, RECOVERY }

    private var step = Step.SIGN_IN
    private var mfaToken: String? = null

    init {
        views.loginButton.setOnClickListener { onButtonClicked() }
        views.codeInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                onButtonClicked()
                true
            } else {
                false
            }
        }
        showStep(Step.SIGN_IN)
    }

    private fun onButtonClicked() {
        when (step) {
            Step.SIGN_IN -> signInWithGoogle()
            Step.CODE -> submitCode()
            Step.SETUP -> confirmSetup()
            Step.RECOVERY -> finishLogin()
        }
    }

    private fun signInWithGoogle() {
        val serverClientId = BuildConfig.GOOGLE_SERVER_CLIENT_ID
        if (serverClientId.isBlank()) {
            showError(activity.getString(R.string.login_config_missing))
            return
        }
        setBusy(true)
        activity.lifecycleScope.launch {
            // Cancelar o seletor de conta, ou falha do Google, caem no mesmo erro.
            var failure: String? = null
            val idToken = try {
                requestGoogleIdToken(serverClientId)
            } catch (e: Exception) {
                failure = "${e.javaClass.simpleName}: ${e.message}"
                null
            }
            if (idToken == null) {
                setBusy(false)
                // Mostra o motivo técnico para diagnosticar (ex.: SHA-1 ou pacote não cadastrados no Google Cloud).
                showError(activity.getString(R.string.login_error), failure)
                return@launch
            }
            io.execute {
                val result = relay.signInWithGoogle(idToken)
                activity.runOnUiThread { handleLoginResult(result) }
            }
        }
    }

    // Seletor de conta do Google dentro do próprio app (sem navegador nem redirecionamento).
    private suspend fun requestGoogleIdToken(serverClientId: String): String? {
        val option = GetSignInWithGoogleOption.Builder(serverClientId).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val response = CredentialManager.create(activity).getCredential(activity, request)
        val credential = response.credential
        if (credential !is CustomCredential ||
            credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            return null
        }
        return GoogleIdTokenCredential.createFrom(credential.data).idToken
    }

    private fun handleLoginResult(result: RelayClient.LoginResult) {
        setBusy(false)
        when (result) {
            RelayClient.LoginResult.Done -> finishLogin()
            is RelayClient.LoginResult.NeedsCode -> {
                mfaToken = result.mfaToken
                showStep(Step.CODE)
            }
            RelayClient.LoginResult.NeedsSetup -> startSetup()
            RelayClient.LoginResult.Failed -> showError(activity.getString(R.string.login_error), relay.lastError)
        }
    }

    private fun submitCode() {
        val token = mfaToken ?: return
        val code = enteredCode() ?: return
        setBusy(true)
        io.execute {
            val verified = relay.verifyCode(token, code)
            activity.runOnUiThread {
                setBusy(false)
                if (verified) {
                    mfaToken = null
                    finishLogin()
                } else {
                    showError(activity.getString(R.string.mfa_failed))
                }
            }
        }
    }

    // Conta sem 2FA ativo: mostra o QR code para o app autenticador.
    private fun startSetup() {
        setBusy(true)
        io.execute {
            val setup = relay.startTwoFactorSetup()
            activity.runOnUiThread {
                setBusy(false)
                if (setup == null) {
                    showError(activity.getString(R.string.setup_failed), relay.lastError)
                    return@runOnUiThread
                }
                showQrCode(setup.first)
                showStep(Step.SETUP, activity.getString(R.string.setup_prompt, setup.second))
            }
        }
    }

    private fun confirmSetup() {
        val code = enteredCode() ?: return
        setBusy(true)
        io.execute {
            val codes = relay.confirmTwoFactor(code)
            activity.runOnUiThread {
                setBusy(false)
                if (codes == null) {
                    showError(activity.getString(R.string.mfa_failed))
                } else {
                    // A sessão já está completa; os códigos são mostrados uma vez.
                    showStep(Step.RECOVERY, activity.getString(R.string.recovery_prompt, codes.joinToString("\n")))
                }
            }
        }
    }

    // Login concluído: registra o aparelho na conta e volta o cartão ao começo.
    private fun finishLogin() {
        setBusy(true)
        io.execute {
            val registered = relay.registerDevice()
            activity.runOnUiThread {
                setBusy(false)
                showStep(Step.SIGN_IN)
                onSignedIn(registered)
            }
        }
    }

    private fun enteredCode(): String? {
        val code = views.codeInput.text?.toString()?.trim().orEmpty()
        if (code.isEmpty()) {
            showError(activity.getString(R.string.mfa_fill))
            return null
        }
        return code
    }

    private fun showQrCode(dataUrl: String) {
        val bytes = Base64.decode(dataUrl.substringAfter(","), Base64.DEFAULT)
        views.qrImage.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    }

    // Uma etapa por vez: título, instrução, campo e botão daquela etapa.
    private fun showStep(newStep: Step, message: String? = null) {
        step = newStep
        val (title, button, hint) = when (newStep) {
            Step.SIGN_IN -> Triple(R.string.login_title, R.string.login_button, R.string.login_hint)
            Step.CODE -> Triple(R.string.mfa_title, R.string.mfa_button, R.string.mfa_prompt)
            Step.SETUP -> Triple(R.string.setup_title, R.string.setup_button, R.string.setup_prompt)
            Step.RECOVERY -> Triple(R.string.recovery_title, R.string.recovery_done_button, R.string.recovery_prompt)
        }
        val needsCode = newStep == Step.CODE || newStep == Step.SETUP
        views.loginTitle.setText(title)
        views.loginButton.setText(button)
        views.loginMessage.text = message ?: activity.getString(hint)
        views.codeInput.text?.clear()
        views.codeLayout.visibility = if (needsCode) View.VISIBLE else View.GONE
        views.qrImage.visibility = if (newStep == Step.SETUP) View.VISIBLE else View.GONE
        showError(null)
        if (needsCode) views.codeInput.requestFocus()
    }

    private fun showError(text: String?, detail: String? = null) {
        views.loginError.text = if (text == null) "" else text + (detail?.let { "\n($it)" } ?: "")
        views.loginError.visibility = if (text != null) View.VISIBLE else View.GONE
    }

    private fun setBusy(busy: Boolean) {
        views.loginButton.isEnabled = !busy
        views.loginProgress.visibility = if (busy) View.VISIBLE else View.GONE
        if (busy) showError(null)
    }
}
