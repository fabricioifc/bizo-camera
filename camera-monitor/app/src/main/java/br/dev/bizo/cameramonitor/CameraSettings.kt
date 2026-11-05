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

/** Configurações da câmera, salvas no aparelho. */
object CameraSettings {
    private const val PREFS = "camera_settings"
    private const val KEY_LENS = "lens"
    private const val KEY_QUALITY = "quality"

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

    private inline fun <reified T : Enum<T>> enumValue(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default
}
