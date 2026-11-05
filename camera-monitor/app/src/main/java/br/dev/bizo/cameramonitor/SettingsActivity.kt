package br.dev.bizo.cameramonitor

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import br.dev.bizo.cameramonitor.databinding.ActivitySettingsBinding
import com.google.android.material.snackbar.Snackbar
import java.util.concurrent.Executors

/**
 * Configurações: imagem, detecção de movimento, PIN e conta.
 * Com PIN ativo, a tela principal só abre esta tela depois de pedir o PIN.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val relay by lazy { RelayClient(this) }
    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.versionText.text = getString(R.string.settings_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        setUpImageSettings()
        setUpMotionSettings()

        binding.pinSetButton.setOnClickListener {
            PinDialogs.chooseNewPin(this) { pinChanged(R.string.pin_saved) }
        }
        binding.pinRemoveButton.setOnClickListener {
            PinLock.clear(this)
            pinChanged(R.string.pin_removed)
        }
        binding.signOutButton.setOnClickListener {
            relay.signOut()
            finish()
        }
        binding.serverButton.setOnClickListener {
            ServerDialog.show(this, relay, io) {
                Snackbar.make(binding.root, R.string.server_saved, Snackbar.LENGTH_SHORT).show()
                refresh()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // Com PIN, quem sair do app não volta direto para cá: precisa do PIN de novo.
    override fun onStop() {
        super.onStop()
        if (PinLock.isSet(this) && !isChangingConfigurations) finish()
    }

    private fun refresh() {
        val running = StreamService.isRunning

        val motionOn = CameraSettings.motionEnabled(this)
        binding.sensitivityLabel.isEnabled = motionOn
        setGroupEnabled(binding.sensitivityGroup, motionOn)
        binding.motionHint.setText(if (motionOn) R.string.motion_on_hint else R.string.motion_off_hint)

        val pinSet = PinLock.isSet(this)
        binding.protectionStatus.setText(if (pinSet) R.string.protection_on else R.string.protection_off)
        binding.pinSetButton.setText(if (pinSet) R.string.pin_change else R.string.pin_set)
        binding.pinRemoveButton.visibility = if (pinSet) View.VISIBLE else View.GONE

        binding.serverText.text = ServerDialog.displayName(ServerSettings.url(this))
        binding.accountText.text = relay.email ?: ""
        // Sair da conta com a câmera ligada deixaria o serviço sem sessão: pede para parar antes.
        binding.signOutButton.isEnabled = !running
        binding.signOutHint.visibility = if (running) View.VISIBLE else View.GONE
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

    private fun updateQualityHint() {
        binding.qualityHint.setText(
            when (CameraSettings.quality(this)) {
                Quality.LOW -> R.string.quality_low_hint
                Quality.MEDIUM -> R.string.quality_medium_hint
                Quality.HIGH -> R.string.quality_high_hint
            }
        )
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

    private fun pinChanged(@StringRes message: Int) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
        // Atualiza a notificação (com ou sem o botão Parar) se a câmera estiver ligada.
        if (StreamService.isRunning) startService(Intent(this, StreamService::class.java))
        refresh()
    }

    private fun setGroupEnabled(group: ViewGroup, enabled: Boolean) {
        group.isEnabled = enabled
        for (i in 0 until group.childCount) group.getChildAt(i).isEnabled = enabled
    }
}
