package com.sanabria.motointercom.core.audio

import kotlin.math.max
import kotlin.math.pow

/**
 * Volumen maestro sobre los volúmenes de voz y multimedia (niveles 0..1).
 *
 * El maestro es el más alto de los dos y los mueve a la vez: con voz 0,4 y multimedia 0,6, subir el maestro 0,1 deja
 * 0,5 y 0,7. La diferencia se conserva aunque uno toque fondo: al bajar el maestro del todo y volver a subirlo, los dos
 * recuperan la distancia que tenían. Mover la voz o la multimedia a mano fija una diferencia nueva.
 */
class VolumeLink(voice: Float = 0.8f, media: Float = 0.5f) {
    var master = 0f
        private set
    private var voiceOffset = 0f
    private var mediaOffset = 0f

    init {
        set(voice, media)
    }

    val voice: Float get() = (master + voiceOffset).coerceIn(0f, 1f)
    val media: Float get() = (master + mediaOffset).coerceIn(0f, 1f)

    fun setMaster(level: Float) {
        master = level.coerceIn(0f, 1f)
    }

    fun nudgeMaster(delta: Float) = setMaster(master + delta)

    fun setVoice(level: Float) = set(level.coerceIn(0f, 1f), media)

    fun setMedia(level: Float) = set(voice, level.coerceIn(0f, 1f))

    private fun set(v: Float, m: Float) {
        master = max(v, m)
        voiceOffset = v - master
        mediaOffset = m - master
    }

    companion object {
        /** Margen de los niveles: 1 nivel entero son 40 dB (aproximadamente como las curvas de volumen de Android). */
        const val RANGE_DB = 40f

        /** Ganancia lineal que corresponde a una diferencia de niveles (0 = 0 dB, -1 = -40 dB). */
        fun gain(levelDiff: Float): Float = 10f.pow(levelDiff * RANGE_DB / 20f)
    }
}
