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
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import br.dev.bizo.cameramonitor.databinding.ActivityMainBinding
import br.dev.bizo.cameramonitor.databinding.DialogPinBinding
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
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

    // Prévia da câmera ligada na tela (só com a transmissão parada).
    private var preview: Preview? = null
    private var previewLens: Lens? = null

    private val previewPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refresh() }

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
            if (StreamService.isRunning) requirePin { stopStreaming() } else requestPermissionsAndStart()
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
            requirePin {
                relay.signOut()
                showStep(Step.SIGN_IN)
                refresh()
            }
        }
        binding.pinSetButton.setOnClickListener {
            if (PinLock.isSet(this)) requirePin { chooseNewPin() } else chooseNewPin()
        }
        binding.pinRemoveButton.setOnClickListener {
            requirePin {
                PinLock.clear(this)
                pinChanged(R.string.pin_removed)
            }
        }
        binding.batteryButton.setOnClickListener { requestBatteryExemption() }
        binding.previewPermissionButton.setOnClickListener {
            previewPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
        setUpImageSettings()
        setUpMotionSettings()
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
        // A câmera tem um dono por vez: a prévia sai para o serviço transmitir.
        releasePreview()
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
        binding.imageCard.visibility = visibleIf(signedIn)
        // Com PIN, a imagem não muda durante a transmissão (trocar para a frontal "cegaria" a câmera).
        val imageLocked = running && PinLock.isSet(this)
        setGroupEnabled(binding.lensGroup, !imageLocked)
        setGroupEnabled(binding.qualityGroup, !imageLocked)
        if (imageLocked) binding.qualityHint.setText(R.string.image_locked_hint) else updateQualityHint()

        // Desligar a detecção também enfraquece a vigilância: segue a mesma trava do PIN.
        val motionOn = CameraSettings.motionEnabled(this)
        binding.motionCard.visibility = visibleIf(signedIn)
        binding.motionSwitch.isEnabled = !imageLocked
        setGroupEnabled(binding.sensitivityGroup, !imageLocked && motionOn)
        binding.motionHint.setText(
            when {
                imageLocked -> R.string.motion_locked_hint
                motionOn -> R.string.motion_on_hint
                else -> R.string.motion_off_hint
            }
        )

        val pinSet = PinLock.isSet(this)
        binding.protectionCard.visibility = visibleIf(signedIn)
        binding.protectionStatus.setText(if (pinSet) R.string.protection_on else R.string.protection_off)
        binding.pinSetButton.setText(if (pinSet) R.string.pin_change else R.string.pin_set)
        binding.pinRemoveButton.visibility = visibleIf(pinSet)
        updatePreview()
    }

    // Lente e qualidade: salvas no aparelho; o serviço troca a câmera ao vivo se estiver transmitindo.
    private fun setUpImageSettings() {
        binding.lensGroup.check(
            when (CameraSettings.lens(this)) {
                Lens.BACK -> R.id.lensBack
                Lens.FRONT -> R.id.lensFront
            }
        )
        binding.qualityGroup.check(
            when (CameraSettings.quality(this)) {
                Quality.LOW -> R.id.qualityLow
                Quality.MEDIUM -> R.id.qualityMedium
                Quality.HIGH -> R.id.qualityHigh
            }
        )
        updateQualityHint()

        binding.lensGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            CameraSettings.setLens(this, if (checkedId == R.id.lensFront) Lens.FRONT else Lens.BACK)
            updatePreview()
        }
        binding.qualityGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val quality = when (checkedId) {
                R.id.qualityLow -> Quality.LOW
                R.id.qualityHigh -> Quality.HIGH
                else -> Quality.MEDIUM
            }
            CameraSettings.setQuality(this, quality)
            updateQualityHint()
        }
    }

    private fun setUpMotionSettings() {
        binding.motionSwitch.isChecked = CameraSettings.motionEnabled(this)
        binding.sensitivityGroup.check(
            when (CameraSettings.sensitivity(this)) {
                Sensitivity.LOW -> R.id.sensitivityLow
                Sensitivity.MEDIUM -> R.id.sensitivityMedium
                Sensitivity.HIGH -> R.id.sensitivityHigh
            }
        )
        binding.motionSwitch.setOnCheckedChangeListener { _, checked ->
            CameraSettings.setMotionEnabled(this, checked)
            refresh()
        }
        binding.sensitivityGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val sensitivity = when (checkedId) {
                R.id.sensitivityLow -> Sensitivity.LOW
                R.id.sensitivityHigh -> Sensitivity.HIGH
                else -> Sensitivity.MEDIUM
            }
            CameraSettings.setSensitivity(this, sensitivity)
        }
    }

    private fun updateQualityHint() {
        binding.qualityHint.setText(
            when (CameraSettings.quality(this)) {
                Quality.LOW -> R.string.quality_low_hint
                Quality.MEDIUM -> R.string.quality_medium_hint
                Quality.HIGH -> R.string.quality_high_hint
            }
        )
    }

    // Mostra a prévia com a câmera parada; libera a câmera quando a transmissão começa.
    private fun updatePreview() {
        val wanted = relay.isSignedIn && !StreamService.isRunning
        val permitted = hasCameraPermission()
        binding.previewCard.visibility = visibleIf(wanted)
        binding.previewView.visibility = visibleIf(wanted && permitted)
        binding.previewPermission.visibility = visibleIf(wanted && !permitted)

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val current = preview
            val bound = current != null && provider.isBound(current)
            val show = wanted && permitted && !StreamService.isRunning &&
                lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            val lens = CameraSettings.lens(this)

            if (!show) {
                if (bound) current?.let { provider.unbind(it) }
                preview = null
                return@addListener
            }
            if (bound && previewLens == lens) return@addListener
            if (bound) current?.let { provider.unbind(it) }

            val available = runCatching { provider.hasCamera(lens.selector) }.getOrDefault(false)
            val selector = if (available) lens.selector else CameraSelector.DEFAULT_BACK_CAMERA
            val newPreview = Preview.Builder().build().also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }
            preview = try {
                provider.bindToLifecycle(this, selector, newPreview)
                previewLens = lens
                newPreview
            } catch (e: Exception) {
                null
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun releasePreview() {
        val current = preview ?: return
        preview = null
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({ providerFuture.get().unbind(current) }, ContextCompat.getMainExecutor(this))
    }

    private fun setGroupEnabled(group: ViewGroup, enabled: Boolean) {
        group.isEnabled = enabled
        for (i in 0 until group.childCount) group.getChildAt(i).isEnabled = enabled
    }

    // ----- PIN -----

    /** Executa a ação direto se não houver PIN; senão, pede o PIN antes. */
    private fun requirePin(action: () -> Unit) {
        if (!PinLock.isSet(this)) {
            action()
            return
        }
        promptPin(R.string.pin_current_title, ::checkPin) { action() }
    }

    private fun checkPin(pin: String): String? = when (PinLock.check(this, pin)) {
        PinLock.Result.OK -> null
        PinLock.Result.WRONG -> getString(R.string.pin_wrong)
        PinLock.Result.LOCKED -> getString(R.string.pin_locked, ((PinLock.lockedForMs(this) + 999) / 1000).toInt())
    }

    private fun chooseNewPin() {
        val validFormat = { pin: String -> if (PinLock.isValidFormat(pin)) null else getString(R.string.pin_invalid) }
        promptPin(R.string.pin_new_title, validFormat) { first ->
            val matches = { pin: String -> if (pin == first) null else getString(R.string.pin_mismatch) }
            promptPin(R.string.pin_confirm_title, matches) { pin ->
                PinLock.set(this, pin)
                pinChanged(R.string.pin_saved)
            }
        }
    }

    private fun pinChanged(@StringRes message: Int) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
        // Atualiza a notificação (com ou sem o botão Parar) se a câmera estiver ligada.
        if (StreamService.isRunning) startService(Intent(this, StreamService::class.java))
        refresh()
    }

    /** Caixa de PIN. [validate] devolve a mensagem de erro, ou null se o PIN foi aceito. */
    private fun promptPin(@StringRes title: Int, validate: (String) -> String?, onAccepted: (String) -> Unit) {
        val view = DialogPinBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
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
