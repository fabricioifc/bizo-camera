package br.dev.bizo.cameramonitor

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import br.dev.bizo.cameramonitor.databinding.ActivityMainBinding
import com.google.android.material.color.MaterialColors
import java.util.concurrent.Executors

/**
 * Tela principal: status da câmera, prévia, botão de iniciar/parar e avisos.
 * O login fica no [LoginController]; imagem, movimento, PIN e conta, na [SettingsActivity].
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var login: LoginController
    private val relay by lazy { RelayClient(this) }
    private val io = Executors.newSingleThreadExecutor()

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
        // Abertura com o logotipo; precisa vir antes do super.onCreate.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        login = LoginController(this, binding.login, relay, io) { registered ->
            statusNotice = if (registered) null else getString(R.string.register_error)
            refresh()
        }
        binding.toggleButton.setOnClickListener {
            statusNotice = null
            if (StreamService.isRunning) PinDialogs.requirePin(this) { stopStreaming() } else requestPermissionsAndStart()
        }
        // Com PIN, as configurações também pedem o PIN (lá dá para desligar a detecção ou sair da conta).
        binding.settingsButton.setOnClickListener {
            PinDialogs.requirePin(this) { startActivity(Intent(this, SettingsActivity::class.java)) }
        }
        binding.batteryButton.setOnClickListener { requestBatteryExemption() }
        binding.previewPermissionButton.setOnClickListener {
            previewPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
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
                    val detail = relay.lastError?.takeIf { BuildConfig.DEBUG }?.let { "\n($it)" }.orEmpty()
                    statusNotice = getString(R.string.register_error) + detail
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
        binding.settingsButton.visibility = visibleIf(signedIn)

        // Botão principal: azul para iniciar, vermelho para parar.
        binding.toggleButton.visibility = visibleIf(signedIn)
        binding.toggleButton.setText(if (running) R.string.toggle_stop else R.string.toggle_start)
        val background = if (running) com.google.android.material.R.attr.colorError else androidx.appcompat.R.attr.colorPrimary
        val foreground = if (running) com.google.android.material.R.attr.colorOnError else com.google.android.material.R.attr.colorOnPrimary
        binding.toggleButton.backgroundTintList = ColorStateList.valueOf(MaterialColors.getColor(binding.toggleButton, background))
        binding.toggleButton.setTextColor(MaterialColors.getColor(binding.toggleButton, foreground))

        binding.login.root.visibility = visibleIf(!signedIn)
        updatePreview()
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
