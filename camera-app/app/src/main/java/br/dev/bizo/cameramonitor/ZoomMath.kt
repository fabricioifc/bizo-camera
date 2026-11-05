package br.dev.bizo.cameramonitor

import kotlin.math.atan
import kotlin.math.roundToInt
import kotlin.math.tan

/** Contas do zoom, separadas do Android para poderem ser testadas. */
object ZoomMath {

    /** Ângulo de visão horizontal (radianos) de uma lente: sensor de [sensorWidthMm] e foco de [focalMm]. */
    fun fieldOfView(sensorWidthMm: Float, focalMm: Float): Double = 2 * atan(sensorWidthMm / (2.0 * focalMm))

    /**
     * Zoom equivalente de uma câmera mais aberta em relação à principal (ex.: 0,6 para a grande-angular):
     * quanto da cena da grande-angular cabe na principal.
     */
    fun equivalentZoom(mainFov: Double, otherFov: Double): Float = (tan(mainFov / 2) / tan(otherFov / 2)).toFloat()

    /** "0,6×", "1×", "2,5×". */
    fun label(value: Float): String {
        val rounded = round(value)
        return if (rounded % 1f == 0f) "${rounded.toInt()}×" else rounded.toString().replace('.', ',') + "×"
    }

    /** Arredonda para uma casa decimal (0,6×, 1,5×...). */
    fun round(value: Float): Float = (value * 10).roundToInt() / 10f

    /**
     * Qual câmera usar para o zoom pedido: a principal (zoom de [mainMin] a [mainMax]) ou a mais
     * aberta, se houver, com zoom equivalente [wideFactor] (< 1). Devolve (usaGrandeAngular, zoom na câmera).
     */
    fun choose(zoom: Float, mainMin: Float, mainMax: Float, wideFactor: Float?): Pair<Boolean, Float> {
        if (wideFactor != null && zoom < mainMin && wideFactor < mainMin) {
            // Na grande-angular, 1× equivale a [wideFactor] da principal.
            return true to maxOf(1f, zoom / wideFactor)
        }
        return false to zoom.coerceIn(mainMin, mainMax)
    }

    /** Faixa total oferecida ao usuário: do mais aberto (grande-angular ou zoom mínimo) ao máximo da principal. */
    fun range(mainMin: Float, mainMax: Float, wideFactor: Float?): Pair<Float, Float> {
        val min = if (wideFactor != null) minOf(mainMin, wideFactor) else mainMin
        return round(min) to round(mainMax)
    }
}
