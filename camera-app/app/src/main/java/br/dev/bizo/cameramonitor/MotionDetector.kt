package br.dev.bizo.cameramonitor

import kotlin.math.abs

/**
 * Detecta movimento comparando grades de brilho (0 a 255) de conferências seguidas.
 *
 * Desconta a variação geral de brilho (luz acesa, exposição automática) e exige algumas
 * conferências seguidas com mudança, para ignorar ruído e piscadas de luz.
 * Não depende do Android, para poder ser testado no computador.
 */
class MotionDetector(
    private val pixelThreshold: Int = 25,
    private val requiredStreak: Int = 2,
) {
    private var previous: IntArray? = null
    private var previousMean = 0
    private var streak = 0

    // Pedido de recomeço vindo de outra thread (ex.: câmera trocada); vale na próxima conferência.
    @Volatile private var resetRequested = false

    /** Esquece a grade anterior: a próxima conferência só serve de referência. */
    fun reset() {
        resetRequested = true
    }

    /**
     * Recebe a grade de brilho atual e devolve true quando há movimento.
     * [changedFraction] é a fração mínima dos pontos que precisa mudar (ver [Sensitivity]).
     */
    fun update(luma: IntArray, changedFraction: Float): Boolean {
        if (resetRequested) {
            resetRequested = false
            previous = null
        }
        val mean = if (luma.isEmpty()) 0 else luma.sum() / luma.size
        val last = previous
        val lastMean = previousMean
        previous = luma
        previousMean = mean
        if (last == null || last.size != luma.size) {
            streak = 0
            return false
        }

        var changed = 0
        for (i in luma.indices) {
            if (abs((luma[i] - mean) - (last[i] - lastMean)) > pixelThreshold) changed++
        }
        streak = if (changed >= luma.size * changedFraction) streak + 1 else 0
        return streak >= requiredStreak
    }
}
