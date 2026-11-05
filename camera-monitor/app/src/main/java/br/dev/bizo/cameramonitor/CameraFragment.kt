package br.dev.bizo.cameramonitor

import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import br.dev.bizo.cameramonitor.databinding.FragmentCameraBinding
import com.google.android.material.slider.Slider

/**
 * Aba Câmera: lente, qualidade, rotação, lanterna e detecção de movimento.
 * Tudo fica salvo no aparelho; o serviço aplica na hora, mesmo transmitindo.
 * As mesmas opções podem ser mudadas pelo site: a tela acompanha essas mudanças.
 */
class CameraFragment : Fragment() {

    private var _binding: FragmentCameraBinding? = null
    private val binding get() = _binding!!

    // Mudança vinda do site (ou de outra tela): atualiza os botões.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        if (_binding != null) showSettings()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCameraBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        showSettings()
        listenToChanges()
    }

    override fun onStart() {
        super.onStart()
        CameraSettings.prefs(requireContext()).registerOnSharedPreferenceChangeListener(prefsListener)
        showSettings()
    }

    override fun onStop() {
        CameraSettings.prefs(requireContext()).unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onStop()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    // Marca os botões com o que está salvo. Salvar o mesmo valor de novo não dispara nada.
    private fun showSettings() {
        val context = requireContext()
        binding.lensGroup.check(
            when (CameraSettings.lens(context)) {
                Lens.BACK -> R.id.lensBack
                Lens.FRONT -> R.id.lensFront
            }
        )
        binding.qualityGroup.check(
            when (CameraSettings.quality(context)) {
                Quality.LOW -> R.id.qualityLow
                Quality.MEDIUM -> R.id.qualityMedium
                Quality.HIGH -> R.id.qualityHigh
            }
        )
        binding.rotationGroup.check(
            when (CameraSettings.rotation(context)) {
                Rotation.R0 -> R.id.rotation0
                Rotation.R90 -> R.id.rotation90
                Rotation.R180 -> R.id.rotation180
                Rotation.R270 -> R.id.rotation270
            }
        )
        binding.torchSwitch.isChecked = CameraSettings.torch(context)
        binding.nightSwitch.isChecked = CameraSettings.night(context)
        binding.motionSwitch.isChecked = CameraSettings.motionEnabled(context)
        binding.videoSwitch.isChecked = CameraSettings.motionVideo(context)
        binding.sensitivityGroup.check(
            when (CameraSettings.sensitivity(context)) {
                Sensitivity.LOW -> R.id.sensitivityLow
                Sensitivity.MEDIUM -> R.id.sensitivityMedium
                Sensitivity.HIGH -> R.id.sensitivityHigh
            }
        )
        showZoom()
        binding.qualityHint.setText(
            when (CameraSettings.quality(context)) {
                Quality.LOW -> R.string.quality_low_hint
                Quality.MEDIUM -> R.string.quality_medium_hint
                Quality.HIGH -> R.string.quality_high_hint
            }
        )
        showMotionState()
    }

    // Zoom: faixa do aparelho (descoberta quando a câmera liga), valor e atalhos.
    private fun showZoom() {
        val context = requireContext()
        val (min, max) = CameraSettings.zoomRange(context)
        val top = maxOf(max, min + 0.1f)
        val zoom = ZoomMath.round(CameraSettings.zoom(context).coerceIn(min, top))
        binding.zoomSlider.valueFrom = min
        binding.zoomSlider.valueTo = top
        binding.zoomSlider.value = zoom
        binding.zoomValue.text = ZoomMath.label(zoom)
        binding.zoomMinButton.text = ZoomMath.label(min)
        binding.zoomMinButton.visibility = if (min < 1f) View.VISIBLE else View.GONE
        binding.zoomTwoButton.isEnabled = max >= 2f
        binding.zoomHint.text = when {
            !CameraSettings.zoomRangeKnown(context) -> getString(R.string.zoom_hint_unknown)
            min < 1f -> getString(R.string.zoom_hint_wide, ZoomMath.label(min), ZoomMath.label(max))
            else -> getString(R.string.zoom_hint_no_wide, ZoomMath.label(min), ZoomMath.label(max))
        }
    }

    private fun listenToChanges() {
        val context = requireContext()
        binding.zoomSlider.setLabelFormatter { ZoomMath.label(it) }
        binding.zoomSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) binding.zoomValue.text = ZoomMath.label(value)
        }
        binding.zoomSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit
            override fun onStopTrackingTouch(slider: Slider) {
                CameraSettings.setZoom(context, slider.value)
            }
        })
        binding.zoomMinButton.setOnClickListener { CameraSettings.setZoom(context, CameraSettings.zoomRange(context).first) }
        binding.zoomOneButton.setOnClickListener { CameraSettings.setZoom(context, 1f) }
        binding.zoomTwoButton.setOnClickListener { CameraSettings.setZoom(context, 2f) }
        binding.lensGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            CameraSettings.setLens(context, if (checkedId == R.id.lensFront) Lens.FRONT else Lens.BACK)
        }
        binding.qualityGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val quality = when (checkedId) {
                R.id.qualityLow -> Quality.LOW
                R.id.qualityHigh -> Quality.HIGH
                else -> Quality.MEDIUM
            }
            CameraSettings.setQuality(context, quality)
        }
        binding.rotationGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val rotation = when (checkedId) {
                R.id.rotation90 -> Rotation.R90
                R.id.rotation180 -> Rotation.R180
                R.id.rotation270 -> Rotation.R270
                else -> Rotation.R0
            }
            CameraSettings.setRotation(context, rotation)
        }
        binding.torchSwitch.setOnCheckedChangeListener { _, checked -> CameraSettings.setTorch(context, checked) }
        binding.nightSwitch.setOnCheckedChangeListener { _, checked -> CameraSettings.setNight(context, checked) }
        binding.motionSwitch.setOnCheckedChangeListener { _, checked -> CameraSettings.setMotionEnabled(context, checked) }
        binding.videoSwitch.setOnCheckedChangeListener { _, checked -> CameraSettings.setMotionVideo(context, checked) }
        binding.sensitivityGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val sensitivity = when (checkedId) {
                R.id.sensitivityLow -> Sensitivity.LOW
                R.id.sensitivityHigh -> Sensitivity.HIGH
                else -> Sensitivity.MEDIUM
            }
            CameraSettings.setSensitivity(context, sensitivity)
        }
    }

    // Com a detecção desligada, a sensibilidade fica apagada.
    private fun showMotionState() {
        val context = requireContext()
        val on = CameraSettings.motionEnabled(context)
        binding.motionHint.setText(if (on) R.string.motion_on_hint else R.string.motion_off_hint)
        binding.sensitivityGroup.isEnabled = on
        binding.videoSwitch.isEnabled = on
        binding.videoHint.alpha = if (on) 1f else 0.5f
        for (i in 0 until binding.sensitivityGroup.childCount) binding.sensitivityGroup.getChildAt(i).isEnabled = on
        binding.sensitivityHint.setText(
            when (CameraSettings.sensitivity(context)) {
                Sensitivity.LOW -> R.string.sensitivity_low_hint
                Sensitivity.MEDIUM -> R.string.sensitivity_medium_hint
                Sensitivity.HIGH -> R.string.sensitivity_high_hint
            }
        )
        val alpha = if (on) 1f else 0.5f
        binding.sensitivityLabel.alpha = alpha
        binding.sensitivityHint.alpha = alpha
    }
}
