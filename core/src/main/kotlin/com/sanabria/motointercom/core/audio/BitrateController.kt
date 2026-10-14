package com.sanabria.motointercom.core.audio

/**
 * Calidad de voz adaptativa. Las pérdidas en esta malla no vienen de la radio (L2CAP retransmite) sino de enlaces que
 * no dan abasto y descartan voz vieja de su cola. Si en un periodo se descarta más del [HIGH_LOSS], se baja un escalón
 * de [LADDER] (… 32 → 24 → 16 → 12 → 8 kbps); tras [RAISE_AFTER] periodos limpios se sube uno, hasta el máximo del usuario.
 * Se llama cada 2 s con lo enviado y descartado en ese periodo por todos los enlaces.
 */
class BitrateController(max: Int) {
    var max = max
        private set
    var current = max
        private set

    private var cleanPeriods = 0

    /** Devuelve el bitrate a usar desde ahora. */
    fun update(sent: Long, dropped: Long): Int {
        val total = sent + dropped
        if (total < MIN_FRAMES) return current
        val loss = dropped.toFloat() / total
        when {
            loss > HIGH_LOSS -> {
                current = LADDER.lastOrNull { it < current } ?: current
                cleanPeriods = 0
            }
            loss < LOW_LOSS -> if (++cleanPeriods >= RAISE_AFTER && current < max) {
                current = minOf(max, LADDER.firstOrNull { it > current } ?: max)
                cleanPeriods = 0
            }
            else -> cleanPeriods = 0
        }
        return current
    }

    fun setMax(bps: Int) {
        max = bps
        current = minOf(current, bps).coerceAtLeast(minOf(MIN_BITRATE, bps))
        if (current < max) cleanPeriods = 0
    }

    companion object {
        /** Escalones de calidad: 8–16 kbps es voz de banda ancha; desde 24 kbps Opus da banda completa. */
        val LADDER = listOf(8_000, 12_000, 16_000, 24_000, 32_000, 40_000, 48_000)
        const val MIN_BITRATE = 8_000
        /** Voz de banda completa sin cargar la radio, que comparte con el A2DP de la música. */
        const val DEFAULT = 24_000
        const val HIGH_LOSS = 0.05f
        const val LOW_LOSS = 0.01f
        const val RAISE_AFTER = 5
        const val MIN_FRAMES = 25
    }
}
