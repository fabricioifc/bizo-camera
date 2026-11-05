package br.dev.bizo.cameramonitor

/**
 * Configurações no formato trocado com o servidor (controle remoto pelo site):
 * lente, qualidade e sensibilidade em minúsculas ("back", "medium"…), rotação em graus,
 * detecção e lanterna como true/false.
 *
 * Os parse* devolvem null para valores desconhecidos, que são ignorados.
 */
object RemoteSettings {
    fun name(value: Enum<*>): String = value.name.lowercase()

    fun parseLens(value: Any?): Lens? = parseEnum<Lens>(value)
    fun parseQuality(value: Any?): Quality? = parseEnum<Quality>(value)
    fun parseSensitivity(value: Any?): Sensitivity? = parseEnum<Sensitivity>(value)
    fun parseRotation(value: Any?): Rotation? = (value as? Number)?.toInt()?.let { Rotation.fromDegrees(it) }
    fun parseFlag(value: Any?): Boolean? = value as? Boolean
    fun parseZoom(value: Any?): Float? = (value as? Number)?.toFloat()?.takeIf { it.isFinite() && it in 0.1f..20f }

    private inline fun <reified T : Enum<T>> parseEnum(value: Any?): T? {
        val text = value as? String ?: return null
        return enumValues<T>().firstOrNull { it.name.equals(text, ignoreCase = true) }
    }
}
