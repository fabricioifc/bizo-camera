package br.dev.bizo.cameramonitor

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
import android.view.View
import android.view.inputmethod.EditorInfo
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
import com.google.android.material.color.MaterialColors
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

    // Aviso temporário no cartão de status (ex.: permissão negada); some no próximo refresh de estado.
    private var statusNotice: String? = null

    private val relayListener: () -> Unit = { runOnUiThread { refresh() } }

    // Atualiza o tempo de conexão na tela enquanto o app está aberto.
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            binding.root.postDelayed(this, TICK_MS)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasCameraPermission()) {
            startStreaming()
        } else {
            statusNotice = getString(R.string.need_camera)
            refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toggleButton.setOnClickListener {
            statusNotice = null
            if (StreamService.isRunning) stopStreaming() else requestPermissionsAndStart()
        }
        binding.loginButton.setOnClickListener { onLoginButtonClicked() }
        binding.codeInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                onLoginButtonClicked()
                true
            } else {
                false
            }
        }
        binding.signOutButton.setOnClickListener {
            relay.signOut()
            showStep(Step.SIGN_IN)
            refresh()
        }
        binding.batteryButton.setOnClickListener { requestBatteryExemption() }
        showStep(Step.SIGN_IN)
    }

    override fun onResume() {
        super.onResume()
        RelayClient.addListener(relayListener)
        // A câmera estava ligada (ex.: pausada após reiniciar o celular): abrir o app retoma a transmissão.
        if (StreamService.shouldRun(this) && !StreamService.isRunning && relay.isSignedIn && hasCameraPermission()) {
            startStreaming()
        }
        ticker.run()
    }

    override fun onPause() {
        RelayClient.removeListener(relayListener)
        binding.root.removeCallbacks(ticker)
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
            showLoginError(getString(R.string.login_config_missing))
            return
        }
        setBusy(true)
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
                showLoginError(getString(R.string.login_error), failure)
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
            }
            RelayClient.LoginResult.NeedsSetup -> startSetup()
            RelayClient.LoginResult.Failed -> showLoginError(getString(R.string.login_error), relay.lastError)
        }
    }

    private fun submitCode() {
        val token = mfaToken ?: return
        val code = enteredCode() ?: return
        setBusy(true)
        io.execute {
            val verified = relay.verifyCode(token, code)
            runOnUiThread {
                setBusy(false)
                if (verified) {
                    mfaToken = null
                    finishLogin()
                } else {
                    showLoginError(getString(R.string.mfa_failed))
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
                    showLoginError(getString(R.string.setup_failed), relay.lastError)
                    return@runOnUiThread
                }
                showQrCode(setup.first)
                showStep(Step.SETUP, getString(R.string.setup_prompt, setup.second))
            }
        }
    }

    private fun confirmSetup() {
        val code = enteredCode() ?: return
        setBusy(true)
        io.execute {
            val codes = relay.confirmTwoFactor(code)
            runOnUiThread {
                setBusy(false)
                if (codes == null) {
                    showLoginError(getString(R.string.mfa_failed))
                } else {
                    // A sessão já está completa; os códigos são mostrados uma vez.
                    showStep(Step.RECOVERY, getString(R.string.recovery_prompt, codes.joinToString("\n")))
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
                statusNotice = if (registered) null else getString(R.string.register_error)
                refresh()
            }
        }
    }

    private fun enteredCode(): String? {
        val code = binding.codeInput.text?.toString()?.trim().orEmpty()
        if (code.isEmpty()) {
            showLoginError(getString(R.string.mfa_fill))
            return null
        }
        return code
    }

    private fun showQrCode(dataUrl: String) {
        val bytes = Base64.decode(dataUrl.substringAfter(","), Base64.DEFAULT)
        binding.qrImage.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
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
        binding.loginTitle.setText(title)
        binding.loginButton.setText(button)
        binding.loginMessage.text = message ?: getString(hint)
        binding.codeInput.text?.clear()
        binding.codeLayout.visibility = visibleIf(newStep == Step.CODE || newStep == Step.SETUP)
        binding.qrImage.visibility = visibleIf(newStep == Step.SETUP)
        showLoginError(null)
        if (newStep == Step.CODE || newStep == Step.SETUP) binding.codeInput.requestFocus()
    }

    private fun showLoginError(text: String?, detail: String? = null) {
        binding.loginError.text = if (text == null) "" else text + (detail?.let { "\n($it)" } ?: "")
        binding.loginError.visibility = visibleIf(text != null)
    }

    private fun setBusy(busy: Boolean) {
        binding.loginButton.isEnabled = !busy
        binding.loginProgress.visibility = visibleIf(busy)
        if (busy) showLoginError(null)
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
        binding.toggleButton.isEnabled = false
        io.execute {
            val registered = relay.registerDevice()
            runOnUiThread {
                binding.toggleButton.isEnabled = true
                if (registered) {
                    ContextCompat.startForegroundService(this, Intent(this, StreamService::class.java))
                    binding.root.postDelayed({ refresh() }, 800)
                } else {
                    statusNotice = getString(R.string.register_error)
                    refresh()
                }
            }
        }
    }

    private fun stopStreaming() {
        startService(Intent(this, StreamService::class.java).setAction(StreamService.ACTION_STOP))
        binding.root.postDelayed({ refresh() }, 500)
    }

    private fun refresh() {
        val running = StreamService.isRunning
        val signedIn = relay.isSignedIn

        // Status: cor, título e explicação de acordo com o estado da câmera.
        val (color, title, detail) = when {
            !signedIn -> Triple(R.color.status_off, R.string.status_signed_out_title, R.string.status_signed_out_detail)
            running && !RelayClient.connected ->
                Triple(R.color.status_connecting, R.string.status_connecting_title, R.string.status_connecting_detail)
            running && RelayClient.streaming ->
                Triple(R.color.status_live, R.string.status_live_title, R.string.status_live_detail)
            running -> Triple(R.color.status_ready, R.string.status_ready_title, R.string.status_ready_detail)
            else -> Triple(R.color.status_off, R.string.status_stopped_title, R.string.status_stopped_detail)
        }
        binding.statusDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, color))
        binding.statusTitle.setText(title)
        val connection = buildList {
            val since = RelayClient.connectedSince
            if (running && since > 0) add(getString(R.string.status_uptime, formatDuration(System.currentTimeMillis() - since)))
            val drops = RelayClient.drops
            if (running && drops > 0) add(resources.getQuantityString(R.plurals.status_drops, drops, drops))
            if (running && StreamService.isThrottled) add(getString(R.string.status_hot))
        }.joinToString(" · ")
        binding.statusDetail.text = statusNotice ?: listOf(getString(detail), connection).filter { it.isNotEmpty() }.joinToString("\n")

        binding.batteryCard.visibility = visibleIf(signedIn && !isIgnoringBatteryOptimizations())

        binding.accountText.text = relay.email?.let { getString(R.string.account_label, it) }
        binding.accountText.visibility = visibleIf(signedIn && relay.email != null)

        // Botão principal: azul para iniciar, vermelho para parar.
        binding.toggleButton.visibility = visibleIf(signedIn)
        binding.toggleButton.setText(if (running) R.string.toggle_stop else R.string.toggle_start)
        val background = if (running) com.google.android.material.R.attr.colorError else androidx.appcompat.R.attr.colorPrimary
        val foreground = if (running) com.google.android.material.R.attr.colorOnError else com.google.android.material.R.attr.colorOnPrimary
        binding.toggleButton.backgroundTintList = ColorStateList.valueOf(MaterialColors.getColor(binding.toggleButton, background))
        binding.toggleButton.setTextColor(MaterialColors.getColor(binding.toggleButton, foreground))

        binding.loginPanel.visibility = visibleIf(!signedIn)
        binding.signOutButton.visibility = visibleIf(signedIn && !running)
    }

    private fun formatDuration(ms: Long): String {
        val minutes = ms / 60_000
        return when {
            minutes < 1 -> getString(R.string.duration_less_than_minute)
            minutes < 60 -> getString(R.string.duration_minutes, minutes.toInt())
            else -> getString(R.string.duration_hours, (minutes / 60).toInt(), (minutes % 60).toInt())
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    // Abre o pedido do Android para tirar o app da otimização de bateria.
    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        try {
            startActivity(request)
        } catch (e: ActivityNotFoundException) {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    private fun visibleIf(condition: Boolean) = if (condition) View.VISIBLE else View.GONE

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TICK_MS = 30_000L
    }
}
