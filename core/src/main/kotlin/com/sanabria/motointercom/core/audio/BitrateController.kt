package com.sanabria.motointercom.core.audio

/**
 * Calidad de voz adaptativa. Las pérdidas en esta malla no vienen de la radio (L2CAP retransmite) sino de enlaces que
 * no dan abasto y descartan voz vieja de su cola. Si en un periodo se descarta más del [HIGH_LOSS], se baja un escalón
 * (16 → 12 → 8 kbps); tras [RAISE_AFTER] periodos limpios se sube uno, hasta el máximo del usuario.
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
                current = maxOf(MIN_BITRATE, current - STEP)
                cleanPeriods = 0
            }
            loss < LOW_LOSS -> if (++cleanPeriods >= RAISE_AFTER && current < max) {
                current = minOf(max, current + STEP)
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
        const val MIN_BITRATE = 8_000
        const val STEP = 4_000
        const val HIGH_LOSS = 0.05f
        const val LOW_LOSS = 0.01f
        const val RAISE_AFTER = 5
        const val MIN_FRAMES = 25
    }
}
