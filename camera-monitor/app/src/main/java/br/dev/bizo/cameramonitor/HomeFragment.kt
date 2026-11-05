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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import br.dev.bizo.cameramonitor.databinding.FragmentHomeBinding
import com.google.android.material.color.MaterialColors
import java.util.concurrent.Executors

/** Aba Início: status da câmera, prévia, botão de iniciar/parar, aviso de bateria e resumo dos ajustes. */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val relay by lazy { RelayClient(requireContext()) }
    private val io = Executors.newSingleThreadExecutor()
    private val main get() = requireActivity() as MainActivity

    // Aviso temporário no cartão de status (ex.: permissão negada); some ao iniciar de novo.
    private var statusNotice: String? = null
    private var starting = false

    private val relayListener: () -> Unit = { activity?.runOnUiThread { if (_binding != null) refresh() } }

    // Prévia da câmera na tela (só com a transmissão parada).
    private var preview: Preview? = null
    // Câmera da prévia (lente + grande-angular) e a câmera ligada, para ajustar o zoom sem religar.
    private var previewKey: String? = null
    private var previewCamera: androidx.camera.core.Camera? = null

    private val previewPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refresh() }

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

    // Atualiza o tempo de conexão enquanto a aba está aberta.
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            binding.root.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.toggleButton.setOnClickListener {
            statusNotice = null
            val activity = requireActivity() as AppCompatActivity
            if (StreamService.isRunning) PinDialogs.requirePin(activity) { stopStreaming() } else requestPermissionsAndStart()
        }
        binding.batteryButton.setOnClickListener { requestBatteryExemption() }
        binding.previewPermissionButton.setOnClickListener {
            previewPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
        binding.summaryImage.setOnClickListener { main.openTab(R.id.nav_camera) }
        binding.summaryMotion.setOnClickListener { main.openTab(R.id.nav_camera) }
        binding.summaryProtection.setOnClickListener { main.openTab(R.id.nav_settings) }
    }

    override fun onResume() {
        super.onResume()
        RelayClient.addListener(relayListener)
        if (main.shouldResumeStreaming()) startStreaming()
        ticker.run()
    }

    override fun onPause() {
        RelayClient.removeListener(relayListener)
        binding.root.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroyView() {
        releasePreview()
        _binding = null
        super.onDestroyView()
    }

    private fun requestPermissionsAndStart() {
        val context = requireContext()
        val missing = buildList {
            if (!hasCameraPermission()) add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (missing.isEmpty()) startStreaming() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startStreaming() {
        if (starting) return
        // A câmera tem um dono por vez: a prévia sai para o serviço transmitir.
        releasePreview()
        starting = true
        refresh()
        CameraStarter.start(requireActivity(), relay, io) { error ->
            starting = false
            statusNotice = error
            if (_binding == null) return@start
            refresh()
            binding.root.postDelayed({ if (_binding != null) refresh() }, 800)
        }
    }

    private fun stopStreaming() {
        CameraStarter.stop(requireActivity())
        binding.root.postDelayed({ if (_binding != null) refresh() }, 500)
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
        val context = requireContext()
        binding.statusDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, color))
        binding.statusTitle.setText(title)
        binding.statusDetail.text = statusNotice ?: getString(detail)

        // Conexão: tempo conectada e quedas, só com a câmera ligada.
        val since = RelayClient.connectedSince
        binding.statsRow.visibility = visibleIf(running && since > 0)
        if (running && since > 0) {
            binding.uptimeValue.text = formatDuration(System.currentTimeMillis() - since)
            binding.dropsValue.text = RelayClient.drops.toString()
        }
        binding.hotWarning.visibility = visibleIf(running && StreamService.isThrottled)

        binding.batteryCard.visibility = visibleIf(signedIn && !isIgnoringBatteryOptimizations())

        // Botão principal: azul para iniciar, vermelho para parar.
        binding.toggleButton.isEnabled = !starting
        binding.toggleButton.setText(if (running) R.string.toggle_stop else R.string.toggle_start)
        binding.toggleButton.setIconResource(if (running) R.drawable.ic_stop else R.drawable.ic_play)
        val background = if (running) com.google.android.material.R.attr.colorError else androidx.appcompat.R.attr.colorPrimary
        val foreground = if (running) com.google.android.material.R.attr.colorOnError else com.google.android.material.R.attr.colorOnPrimary
        val onColor = MaterialColors.getColor(binding.toggleButton, foreground)
        binding.toggleButton.backgroundTintList = ColorStateList.valueOf(MaterialColors.getColor(binding.toggleButton, background))
        binding.toggleButton.setTextColor(onColor)
        binding.toggleButton.iconTint = ColorStateList.valueOf(onColor)

        refreshSummary()
        updatePreview()
    }

    // Resumo dos ajustes, com atalhos para as abas Câmera e Ajustes.
    private fun refreshSummary() {
        val context = requireContext()
        val lens = getString(if (CameraSettings.lens(context) == Lens.FRONT) R.string.lens_front else R.string.lens_back)
        binding.summaryImageValue.text = getString(R.string.summary_image, lens, levelName(CameraSettings.quality(context).ordinal))
        binding.summaryMotionValue.text = if (CameraSettings.motionEnabled(context)) {
            getString(R.string.summary_motion_on, levelName(CameraSettings.sensitivity(context).ordinal))
        } else {
            getString(R.string.summary_motion_off)
        }
        binding.summaryProtectionValue.setText(if (PinLock.isSet(context)) R.string.summary_pin_on else R.string.summary_pin_off)
    }

    // Baixa / Média / Alta, na ordem dos enums de qualidade e sensibilidade.
    private fun levelName(ordinal: Int): String = getString(
        when (ordinal) {
            0 -> R.string.quality_low
            1 -> R.string.quality_medium
            else -> R.string.quality_high
        }
    )

    // Mostra a prévia com a câmera parada; libera a câmera quando a transmissão começa.
    private fun updatePreview() {
        val context = requireContext()
        val wanted = relay.isSignedIn && !StreamService.isRunning && !starting
        val permitted = hasCameraPermission()
        binding.previewCard.visibility = visibleIf(wanted)
        binding.previewView.visibility = visibleIf(wanted && permitted)
        binding.previewLabel.visibility = visibleIf(wanted && permitted)
        binding.previewPermission.visibility = visibleIf(wanted && !permitted)

        val owner = viewLifecycleOwner
        val surface = binding.previewView.surfaceProvider
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val current = preview
            val bound = current != null && provider.isBound(current)
            val show = wanted && permitted && !StreamService.isRunning && !starting &&
                owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            // Mesma escolha do serviço: lente e zoom (abaixo de 1×, a grande-angular, se houver).
            val choice = CameraZoom.choose(provider, CameraSettings.lens(context), CameraSettings.zoom(context))
            val key = "${CameraSettings.lens(context)}:${choice.wideCameraId}"

            if (!show) {
                if (bound) current?.let { provider.unbind(it) }
                preview = null
                return@addListener
            }
            if (bound && previewKey == key) {
                previewCamera?.cameraControl?.setZoomRatio(choice.ratio)
                return@addListener
            }
            if (bound) current?.let { provider.unbind(it) }

            val newPreview = Preview.Builder().build().also { it.setSurfaceProvider(surface) }
            preview = try {
                previewCamera = provider.bindToLifecycle(owner, choice.selector, newPreview)
                previewCamera?.cameraControl?.setZoomRatio(choice.ratio)
                CameraSettings.setZoomRange(context, choice.minZoom, choice.maxZoom)
                previewKey = key
                newPreview
            } catch (e: Exception) {
                null
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun releasePreview() {
        val current = preview ?: return
        preview = null
        val context = context ?: return
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({ providerFuture.get().unbind(current) }, ContextCompat.getMainExecutor(context))
    }

    private fun formatDuration(ms: Long): String {
        val minutes = ms / 60_000
        return when {
            minutes < 1 -> getString(R.string.duration_less_than_minute)
            minutes < 60 -> getString(R.string.duration_minutes, minutes.toInt())
            else -> getString(R.string.duration_hours, (minutes / 60).toInt(), (minutes % 60).toInt())
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val context = requireContext()
        return context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }

    // Abre o pedido do Android para tirar o app da otimização de bateria.
    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        val packageName = requireContext().packageName
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        try {
            startActivity(request)
        } catch (e: ActivityNotFoundException) {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    private fun visibleIf(condition: Boolean) = if (condition) View.VISIBLE else View.GONE

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TICK_MS = 30_000L
    }
}
