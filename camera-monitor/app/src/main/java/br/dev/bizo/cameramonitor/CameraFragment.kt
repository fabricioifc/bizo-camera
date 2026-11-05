package br.dev.bizo.cameramonitor

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import br.dev.bizo.cameramonitor.databinding.FragmentCameraBinding

/**
 * Aba Câmera: lente, qualidade e detecção de movimento.
 * Tudo fica salvo no aparelho; o serviço aplica na hora, mesmo transmitindo.
 */
class CameraFragment : Fragment() {

    private var _binding: FragmentCameraBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCameraBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        setUpImage()
        setUpMotion()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun setUpImage() {
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
        updateQualityHint()

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
            updateQualityHint()
        }
    }

    private fun setUpMotion() {
        val context = requireContext()
        binding.motionSwitch.isChecked = CameraSettings.motionEnabled(context)
        binding.sensitivityGroup.check(
            when (CameraSettings.sensitivity(context)) {
                Sensitivity.LOW -> R.id.sensitivityLow
                Sensitivity.MEDIUM -> R.id.sensitivityMedium
                Sensitivity.HIGH -> R.id.sensitivityHigh
            }
        )
        updateMotion()

        binding.motionSwitch.setOnCheckedChangeListener { _, checked ->
            CameraSettings.setMotionEnabled(context, checked)
            updateMotion()
        }
        binding.sensitivityGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val sensitivity = when (checkedId) {
                R.id.sensitivityLow -> Sensitivity.LOW
                R.id.sensitivityHigh -> Sensitivity.HIGH
                else -> Sensitivity.MEDIUM
            }
            CameraSettings.setSensitivity(context, sensitivity)
            updateMotion()
        }
    }

    private fun updateQualityHint() {
        binding.qualityHint.setText(
            when (CameraSettings.quality(requireContext())) {
                Quality.LOW -> R.string.quality_low_hint
                Quality.MEDIUM -> R.string.quality_medium_hint
                Quality.HIGH -> R.string.quality_high_hint
            }
        )
    }

    // Com a detecção desligada, a sensibilidade fica apagada.
    private fun updateMotion() {
        val context = requireContext()
        val on = CameraSettings.motionEnabled(context)
        binding.motionHint.setText(if (on) R.string.motion_on_hint else R.string.motion_off_hint)
        binding.sensitivityLabel.isEnabled = on
        binding.sensitivityHint.isEnabled = on
        binding.sensitivityGroup.isEnabled = on
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
