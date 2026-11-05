package br.dev.bizo.cameramonitor

/**
 * Contas do modo noturno, separadas do Android para poderem ser testadas.
 *
 * No escuro, a câmera clareia a imagem de dois jeitos: deixando cada quadro exposto por mais
 * tempo (menos quadros por segundo) e compensando a exposição para cima.
 */
object NightMode {

    /**
     * Faixa de quadros por segundo para o escuro: a que deixa cair mais (menor mínimo), o que
     * permite exposições mais longas. Empate: a de maior máximo, para a imagem não travar na luz.
     * Recebe pares (mínimo, máximo); devolve null se não houver nenhuma.
     */
    fun fpsRange(ranges: List<Pair<Int, Int>>): Pair<Int, Int>? =
        ranges.filter { it.first in 1..it.second }
            .minWithOrNull(compareBy<Pair<Int, Int>> { it.first }.thenByDescending { it.second })

    /** Compensação de exposição: dois terços do máximo do aparelho (o máximo costuma estourar luzes). */
    fun exposureIndex(lower: Int, upper: Int): Int {
        if (upper <= 0) return 0
        return ((upper * 2 + 2) / 3).coerceIn(maxOf(lower, 0), upper)
    }
}
