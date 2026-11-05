package br.dev.bizo.cameramonitor

import kotlin.math.abs

/**
 * Posição do celular (em pé, deitado para um lado ou para o outro, de cabeça para baixo),
 * para a imagem enviada sair na orientação certa mesmo com o celular preso de lado.
 *
 * Usa os mesmos valores de android.view.Surface.ROTATION_* (0, 1, 2, 3), que o CameraX
 * recebe como "targetRotation".
 */
object DeviceOrientation {
    const val ROTATION_0 = 0 // em pé (posição natural)
    const val ROTATION_90 = 1 // deitado, com o topo para a esquerda
    const val ROTATION_180 = 2 // de cabeça para baixo
    const val ROTATION_270 = 3 // deitado, com o topo para a direita

    /** Margem além dos 45° antes de trocar de posição, para não ficar alternando perto da diagonal. */
    private const val MARGIN = 15

    // Ângulo (do OrientationEventListener, sentido horário) no centro de cada posição.
    private val CENTERS = mapOf(ROTATION_0 to 0, ROTATION_270 to 90, ROTATION_180 to 180, ROTATION_90 to 270)

    /**
     * Posição para o ângulo [orientation] (0 a 359, ou negativo se o celular estiver deitado
     * de costas/de frente e o ângulo for desconhecido), partindo da posição [current].
     */
    fun rotationFor(orientation: Int, current: Int): Int {
        if (orientation < 0) return current
        val angle = orientation % 360
        val currentCenter = CENTERS[current] ?: return nearest(angle)
        if (distance(angle, currentCenter) <= 45 + MARGIN) return current
        return nearest(angle)
    }

    private fun nearest(angle: Int): Int = CENTERS.minByOrNull { distance(angle, it.value) }!!.key

    private fun distance(a: Int, b: Int): Int {
        val diff = abs(a - b) % 360
        return minOf(diff, 360 - diff)
    }
}
