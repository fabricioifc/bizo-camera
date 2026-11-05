package br.dev.bizo.cameramonitor

import android.content.Context
import android.content.SharedPreferences
import android.util.Size
import androidx.camera.core.CameraSelector

/** Lente usada para transmitir. */
enum class Lens(val selector: CameraSelector) {
    BACK(CameraSelector.DEFAULT_BACK_CAMERA),
    FRONT(CameraSelector.DEFAULT_FRONT_CAMERA),
}

/** Qualidade da transmissão: resolução, intervalo entre quadros e qualidade do JPEG. */
enum class Quality(val resolution: Size, val frameIntervalMs: Long, val jpegQuality: Int) {
    LOW(Size(320, 240), 200L, 50), // ~5 fps, pouco dado no LTE
    MEDIUM(Size(640, 480), 100L, 60), // ~10 fps
    HIGH(Size(1280, 720), 100L, 70), // ~10 fps em HD; precisa de Wi-Fi bom
}

/** Sensibilidade da detecção: fração da imagem que precisa mudar para contar como movimento. */
enum class Sensitivity(val changedFraction: Float) {
    LOW(0.08f),
    MEDIUM(0.04f),
    HIGH(0.02f),
}

/** Rotação extra da imagem enviada, para o celular preso de lado ou de cabeça para baixo. */
enum class Rotation(val degrees: Int) {
    R0(0),
    R90(90),
    R180(180),
    R270(270);

    companion object {
        fun fromDegrees(degrees: Int): Rotation? = values().firstOrNull { it.degrees == degrees }
    }
}

/** Configurações da câmera, salvas no aparelho. */
object CameraSettings {
    private const val PREFS = "camera_settings"
    private const val KEY_LENS = "lens"
    private const val KEY_QUALITY = "quality"
    private const val KEY_MOTION = "motion"
    private const val KEY_SENSITIVITY = "sensitivity"
    private const val KEY_ROTATION = "rotation"
    private const val KEY_TORCH = "torch"
    private const val KEY_VIDEO = "video"
    const val KEY_NIGHT = "night"
    const val KEY_ZOOM = "zoom"
    private const val KEY_ZOOM_MIN = "zoomMin"
    private const val KEY_ZOOM_MAX = "zoomMax"

    /** Lente, qualidade e modo noturno exigem religar a câmera; as demais configurações valem na hora. */
    fun affectsCamera(key: String?) = key == KEY_LENS || key == KEY_QUALITY || key == KEY_NIGHT

    fun motionEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_MOTION, true)

    fun setMotionEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MOTION, enabled).apply()
    }

    fun sensitivity(context: Context): Sensitivity =
        enumValue(prefs(context).getString(KEY_SENSITIVITY, null), Sensitivity.MEDIUM)

    fun setSensitivity(context: Context, sensitivity: Sensitivity) {
        prefs(context).edit().putString(KEY_SENSITIVITY, sensitivity.name).apply()
    }

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lens(context: Context): Lens =
        enumValue(prefs(context).getString(KEY_LENS, null), Lens.BACK)

    fun quality(context: Context): Quality =
        enumValue(prefs(context).getString(KEY_QUALITY, null), Quality.MEDIUM)

    fun setLens(context: Context, lens: Lens) {
        prefs(context).edit().putString(KEY_LENS, lens.name).apply()
    }

    fun setQuality(context: Context, quality: Quality) {
        prefs(context).edit().putString(KEY_QUALITY, quality.name).apply()
    }

    fun rotation(context: Context): Rotation =
        enumValue(prefs(context).getString(KEY_ROTATION, null), Rotation.R0)

    fun setRotation(context: Context, rotation: Rotation) {
        prefs(context).edit().putString(KEY_ROTATION, rotation.name).apply()
    }

    /** Lanterna pedida; só acende com a câmera ligada e numa lente com flash. */
    fun torch(context: Context): Boolean = prefs(context).getBoolean(KEY_TORCH, false)

    fun setTorch(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_TORCH, on).apply()
    }

    /** Gravar o vídeo curto do movimento (além da foto). */
    fun motionVideo(context: Context): Boolean = prefs(context).getBoolean(KEY_VIDEO, true)

    fun setMotionVideo(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_VIDEO, on).apply()
    }

    /** Modo noturno: exposição para lugar escuro e tela do celular apagada enquanto transmite. */
    fun night(context: Context): Boolean = prefs(context).getBoolean(KEY_NIGHT, false)

    fun setNight(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_NIGHT, on).apply()
    }

    /** Zoom pedido (1 = normal; abaixo de 1 usa a grande-angular, se houver). */
    fun zoom(context: Context): Float = prefs(context).getFloat(KEY_ZOOM, 1f)

    fun setZoom(context: Context, zoom: Float) {
        prefs(context).edit().putFloat(KEY_ZOOM, ZoomMath.round(zoom)).apply()
    }

    /** Faixa de zoom do aparelho, guardada quando a câmera liga (antes disso, 1× a 4×). */
    fun zoomRange(context: Context): Pair<Float, Float> =
        prefs(context).getFloat(KEY_ZOOM_MIN, 1f) to prefs(context).getFloat(KEY_ZOOM_MAX, 4f)

    /** O app já ligou a câmera neste celular e sabe a faixa de zoom real. */
    fun zoomRangeKnown(context: Context): Boolean = prefs(context).contains(KEY_ZOOM_MIN)

    fun setZoomRange(context: Context, min: Float, max: Float) {
        val (oldMin, oldMax) = zoomRange(context)
        if (zoomRangeKnown(context) && oldMin == min && oldMax == max) return
        prefs(context).edit().putFloat(KEY_ZOOM_MIN, min).putFloat(KEY_ZOOM_MAX, max).apply()
    }

    private inline fun <reified T : Enum<T>> enumValue(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default
}
