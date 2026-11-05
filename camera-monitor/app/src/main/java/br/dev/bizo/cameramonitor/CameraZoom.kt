package br.dev.bizo.cameramonitor

import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider

/**
 * Escolhe a câmera e o zoom: de 1× para cima, zoom na câmera principal; abaixo de 1×
 * (ex.: 0,6×), a câmera traseira mais aberta (grande-angular), se o celular deixar outros apps
 * usarem. Muitos celulares já expõem tudo como uma câmera só, com zoom mínimo abaixo de 1.
 */
@OptIn(ExperimentalCamera2Interop::class)
object CameraZoom {

    /** Câmera para usar ([selector], [cameraId] só para a grande-angular) e o zoom nela ([ratio]). */
    data class Choice(val selector: CameraSelector, val wideCameraId: String?, val ratio: Float, val minZoom: Float, val maxZoom: Float)

    fun choose(provider: ProcessCameraProvider, lens: Lens, zoom: Float): Choice {
        val wanted = if (lens == Lens.FRONT && hasCamera(provider, CameraSelector.DEFAULT_FRONT_CAMERA)) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        val main = runCatching { wanted.filter(provider.availableCameraInfos).firstOrNull() }.getOrNull()
            ?: return Choice(wanted, null, 1f, 1f, 1f)
        val (mainMin, mainMax) = zoomRange(main)
        val wide = if (wanted == CameraSelector.DEFAULT_BACK_CAMERA) widerBackCamera(provider, main) else null

        val (useWide, ratio) = ZoomMath.choose(zoom, mainMin, mainMax, wide?.second)
        val (min, max) = ZoomMath.range(mainMin, mainMax, wide?.second)
        if (useWide && wide != null) {
            val id = wide.first
            val selector = CameraSelector.Builder()
                .addCameraFilter { infos -> infos.filter { Camera2CameraInfo.from(it).cameraId == id } }
                .build()
            return Choice(selector, id, ratio, min, max)
        }
        return Choice(wanted, null, ratio, min, max)
    }

    private fun hasCamera(provider: ProcessCameraProvider, selector: CameraSelector) =
        runCatching { provider.hasCamera(selector) }.getOrDefault(false)

    // Zoom mínimo e máximo da câmera, pelas características do Camera2 (vale antes de ligar a câmera).
    private fun zoomRange(info: CameraInfo): Pair<Float, Float> {
        val camera2 = Camera2CameraInfo.from(info)
        if (Build.VERSION.SDK_INT >= 30) {
            camera2.getCameraCharacteristic(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let {
                return it.lower to it.upper
            }
        }
        val maxDigital = camera2.getCameraCharacteristic(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        return 1f to maxOf(1f, maxDigital)
    }

    private fun fieldOfView(info: CameraInfo): Double? {
        val camera2 = Camera2CameraInfo.from(info)
        val focal = camera2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.minOrNull() ?: return null
        val sensor = camera2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        if (focal <= 0f || sensor.width <= 0f) return null
        return ZoomMath.fieldOfView(sensor.width, focal)
    }

    // Câmera traseira mais aberta que a principal (pelo menos 15% a mais de ângulo), com o zoom equivalente.
    private fun widerBackCamera(provider: ProcessCameraProvider, main: CameraInfo): Pair<String, Float>? {
        val mainFov = fieldOfView(main) ?: return null
        val mainId = Camera2CameraInfo.from(main).cameraId
        return provider.availableCameraInfos
            .filter { it.lensFacing == CameraSelector.LENS_FACING_BACK }
            .mapNotNull { info ->
                val id = Camera2CameraInfo.from(info).cameraId
                val fov = fieldOfView(info)
                if (id == mainId || fov == null || fov < mainFov * 1.15) null else id to fov
            }
            .maxByOrNull { it.second }
            ?.let { (id, fov) -> id to ZoomMath.round(ZoomMath.equivalentZoom(mainFov, fov)) }
    }
}
