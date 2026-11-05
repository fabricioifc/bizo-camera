package br.dev.bizo.cameramonitor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.lifecycle.lifecycleScope
import br.dev.bizo.cameramonitor.databinding.ActivityMainBinding
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    // Etapas do login: entrar com o Google, código 2FA, configurar 2FA e guardar os códigos de recuperação.
    private enum class Step { SIGN_IN, CODE, SETUP, RECOVERY }

    private lateinit var binding: ActivityMainBinding
    private val relay by lazy { RelayClient(this) }
    private val io = Executors.newSingleThreadExecutor()

    private var step = Step.SIGN_IN
    private var mfaToken: String? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasCameraPermission()) {
            startStreaming()
        } else {
            binding.infoText.text = getString(R.string.need_camera)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toggleButton.setOnClickListener {
            when {
                StreamService.isRunning -> stopStreaming()
                !relay.isSignedIn -> binding.loginMessage.text = getString(R.string.need_login)
                else -> requestPermissionsAndStart()
            }
        }
        binding.loginButton.setOnClickListener { onLoginButtonClicked() }
        binding.signOutButton.setOnClickListener {
            relay.signOut()
            refresh()
        }
        showStep(Step.SIGN_IN)
    }

    override fun onResume() {
        super.onResume()
        RelayClient.onChange = { runOnUiThread { refresh() } }
        refresh()
    }

    override fun onPause() {
        RelayClient.onChange = null
        super.onPause()
    }

    private fun onLoginButtonClicked() {
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
            binding.loginMessage.text = getString(R.string.login_config_missing)
            return
        }
        setBusy(true)
        binding.loginMessage.text = getString(R.string.login_loading)
        lifecycleScope.launch {
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
                binding.loginMessage.text = getString(R.string.login_error) + (failure?.let { "\n($it)" } ?: "")
                return@launch
            }
            io.execute {
                val result = relay.signInWithGoogle(idToken)
                runOnUiThread { handleLoginResult(result) }
            }
        }
    }

    // Seletor de conta do Google dentro do próprio app (sem navegador nem redirecionamento).
    private suspend fun requestGoogleIdToken(serverClientId: String): String? {
        val option = GetSignInWithGoogleOption.Builder(serverClientId).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val response = CredentialManager.create(this).getCredential(this, request)
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
                binding.loginMessage.text = getString(R.string.mfa_prompt)
            }
            RelayClient.LoginResult.NeedsSetup -> startSetup()
            RelayClient.LoginResult.Failed -> binding.loginMessage.text = getString(R.string.login_error)
        }
    }

    private fun submitCode() {
        val token = mfaToken ?: return
        val code = binding.codeInput.text.toString().trim()
        if (code.isEmpty()) {
            binding.loginMessage.text = getString(R.string.mfa_fill)
            return
        }
        setBusy(true)
        io.execute {
            val verified = relay.verifyCode(token, code)
            runOnUiThread {
                setBusy(false)
                if (verified) {
                    mfaToken = null
                    finishLogin()
                } else {
                    binding.loginMessage.text = getString(R.string.mfa_failed)
                }
            }
        }
    }

    // Conta sem 2FA ativo: mostra o QR code para o app autenticador.
    private fun startSetup() {
        setBusy(true)
        io.execute {
            val setup = relay.startTwoFactorSetup()
            runOnUiThread {
                setBusy(false)
                if (setup == null) {
                    binding.loginMessage.text = getString(R.string.setup_failed)
                    return@runOnUiThread
                }
                showQrCode(setup.first)
                showStep(Step.SETUP)
                binding.loginMessage.text = getString(R.string.setup_prompt, setup.second)
            }
        }
    }

    private fun confirmSetup() {
        val code = binding.codeInput.text.toString().trim()
        if (code.isEmpty()) {
            binding.loginMessage.text = getString(R.string.mfa_fill)
            return
        }
        setBusy(true)
        io.execute {
            val codes = relay.confirmTwoFactor(code)
            runOnUiThread {
                setBusy(false)
                if (codes == null) {
                    binding.loginMessage.text = getString(R.string.mfa_failed)
                } else {
                    // A sessão já está completa; os códigos são mostrados uma vez.
                    showStep(Step.RECOVERY)
                    binding.loginMessage.text = getString(R.string.recovery_prompt, codes.joinToString("\n"))
                }
            }
        }
    }

    private fun finishLogin() {
        setBusy(true)
        io.execute {
            val registered = relay.registerDevice()
            runOnUiThread {
                setBusy(false)
                showStep(Step.SIGN_IN)
                binding.loginMessage.text = if (registered) "" else getString(R.string.register_error)
                refresh()
            }
        }
    }

    private fun showQrCode(dataUrl: String) {
        val bytes = Base64.decode(dataUrl.substringAfter(","), Base64.DEFAULT)
        binding.qrImage.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    }

    // Uma etapa por vez: mostra só o campo e o botão que ela precisa.
    private fun showStep(newStep: Step) {
        step = newStep
        binding.codeInput.text?.clear()
        binding.codeInput.visibility = if (newStep == Step.CODE || newStep == Step.SETUP) View.VISIBLE else View.GONE
        binding.qrImage.visibility = if (newStep == Step.SETUP) View.VISIBLE else View.GONE
        binding.loginButton.setText(
            when (newStep) {
                Step.SIGN_IN -> R.string.login_button
                Step.CODE -> R.string.mfa_button
                Step.SETUP -> R.string.setup_button
                Step.RECOVERY -> R.string.recovery_done_button
            }
        )
    }

    private fun setBusy(busy: Boolean) {
        binding.loginButton.isEnabled = !busy
    }

    private fun requestPermissionsAndStart() {
        val missing = buildList {
            if (!hasCameraPermission()) add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(
                    this@MainActivity, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (missing.isEmpty()) startStreaming() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startStreaming() {
        // Registra o aparelho antes de subir o serviço, para ele já conectar.
        io.execute {
            val registered = relay.registerDevice()
            runOnUiThread {
                if (registered) {
                    ContextCompat.startForegroundService(this, Intent(this, StreamService::class.java))
                    binding.infoText.postDelayed({ refresh() }, 800)
                } else {
                    binding.infoText.text = getString(R.string.register_error)
                }
            }
        }
    }

    private fun stopStreaming() {
        startService(Intent(this, StreamService::class.java).setAction(StreamService.ACTION_STOP))
        binding.infoText.postDelayed({ refresh() }, 500)
    }

    private fun refresh() {
        val running = StreamService.isRunning
        val signedIn = relay.isSignedIn

        binding.toggleButton.setText(if (running) R.string.toggle_stop else R.string.toggle_start)
        binding.loginPanel.visibility = if (signedIn) View.GONE else View.VISIBLE
        binding.signOutButton.visibility = if (signedIn && !running) View.VISIBLE else View.GONE
        binding.infoText.text = when {
            running && !RelayClient.connected -> getString(R.string.status_connecting)
            running && RelayClient.streaming -> getString(R.string.status_live)
            running -> getString(R.string.status_connected)
            !signedIn -> getString(R.string.need_login)
            else -> getString(R.string.stopped)
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
}
