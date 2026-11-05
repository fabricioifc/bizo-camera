package br.dev.bizo.cameramonitor

/**
 * Limite de quadros e de qualidade do JPEG imposto pelo calor do celular.
 * Soma-se à qualidade escolhida pelo usuário: vale o mais econômico dos dois.
 */
data class ThermalLimit(val minFrameIntervalMs: Long, val maxJpegQuality: Int) {

    val throttled: Boolean get() = this != NONE

    companion object {
        val NONE = ThermalLimit(minFrameIntervalMs = 0L, maxJpegQuality = 100)
        private val WARM = ThermalLimit(minFrameIntervalMs = 250L, maxJpegQuality = 50) // ~4 fps
        private val HOT = ThermalLimit(minFrameIntervalMs = 1_000L, maxJpegQuality = 40) // 1 fps
        private const val WARM_CELSIUS = 40.0
        private const val HOT_CELSIUS = 44.0

        /** Celular quente: menos quadros e JPEG menor, para gerar menos calor. Volta ao normal ao esfriar. */
        fun forTemperature(celsius: Double?): ThermalLimit = when {
            celsius == null || celsius < WARM_CELSIUS -> NONE
            celsius < HOT_CELSIUS -> WARM
            else -> HOT
        }
    }
}
