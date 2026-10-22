package com.sanabria.motointercom.core.audio

import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Control automático de la ganancia del micro. El micro del casco (sobre todo por manos libres) llega muy bajo y nada lo
 * sube por el camino, así que los demás apenas oyen. Mientras se habla ([voice]) lleva el nivel de la voz hacia
 * [TARGET_DB]: sube despacio y baja deprisa, para no recortar un grito. Entre palabras y con ruido de fondo la ganancia no
 * se mueve (si no, subiría el viento). Nunca atenúa y no pasa de [MAX_GAIN_DB].
 */
class MicGain(private val frameSamples: Int = VoiceFormat.FRAME_SAMPLES) {

    /** Ganancia actual en dB. */
    var gainDb = 0f
        private set

    /** Aplica la ganancia a [pcm] en su sitio. [voice] = el detector de voz está abierto. */
    fun process(pcm: ShortArray, voice: Boolean) {
        var sum = 0.0
        var peak = 0
        for (i in 0 until frameSamples) {
            val s = pcm[i].toInt()
            sum += s.toDouble() * s
            val a = if (s < 0) -s else s
            if (a > peak) peak = a
        }
        val rms = sqrt(sum / frameSamples)
        if (voice && rms > MIN_RMS) {
            val levelDb = 20f * log10((rms / 32768.0)).toFloat()
            var wanted = (TARGET_DB - levelDb).coerceIn(0f, MAX_GAIN_DB)
            // Sin pasar nunca del tope de la señal con la ganancia que ya hay (los picos mandan más que la media)
            val headroomDb = 20f * log10(32767f / peak.coerceAtLeast(1)).coerceAtLeast(0f)
            wanted = minOf(wanted, headroomDb + CREST_DB)
            gainDb = if (wanted > gainDb) minOf(gainDb + UP_DB_PER_FRAME, wanted) else maxOf(gainDb - DOWN_DB_PER_FRAME, wanted)
        }
        if (gainDb < 0.01f) return
        val g = 10f.pow(gainDb / 20f)
        for (i in 0 until frameSamples) pcm[i] = (Mixer.softClip(pcm[i] * g / 32768f) * 32767f).toInt().toShort()
    }

    fun reset() {
        gainDb = 0f
    }

    companion object {
        /** Nivel de voz al que se lleva el micro (RMS, dBFS): alto sin tocar los picos. */
        const val TARGET_DB = -18f
        const val MAX_GAIN_DB = 18f

        /** Subida 6 dB/s y bajada 60 dB/s con tramas de 20 ms. */
        const val UP_DB_PER_FRAME = 0.12f
        const val DOWN_DB_PER_FRAME = 1.2f

        /** Por debajo de esto no hay voz de verdad (RMS en escala de 16 bits): no se ajusta. */
        const val MIN_RMS = 30.0

        /** Lo que el limitador suave deja pasar por encima del pico sin distorsionar de forma audible. */
        const val CREST_DB = 6f
    }
}
