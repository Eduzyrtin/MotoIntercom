package com.sanabria.motointercom.core.audio

import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.tanh

/**
 * Suma las voces de varios emisores en una trama, aplica la ganancia del volumen de voces y un limitador suave
 * (por encima de [KNEE] comprime en vez de recortar: dos voces fuertes a la vez no distorsionan).
 */
class Mixer(private val frameSamples: Int = VoiceFormat.FRAME_SAMPLES) {
    private val acc = FloatArray(frameSamples)
    private var sources = 0

    fun begin() {
        acc.fill(0f)
        sources = 0
    }

    fun add(pcm: ShortArray, n: Int = frameSamples) {
        for (i in 0 until minOf(n, frameSamples)) acc[i] += pcm[i].toFloat()
        sources++
    }

    /** Escribe la mezcla en [out]. Devuelve false (y silencio) si no había ninguna voz. */
    fun end(gain: Float, out: ShortArray): Boolean {
        if (sources == 0) {
            out.fill(0, 0, frameSamples)
            return false
        }
        val g = gain / 32768f
        for (i in 0 until frameSamples) out[i] = (softClip(acc[i] * g) * 32767f).toInt().toShort()
        return true
    }

    companion object {
        const val KNEE = 0.7f

        fun softClip(x: Float): Float {
            val a = abs(x)
            if (a <= KNEE) return x
            return sign(x) * (KNEE + (1f - KNEE) * tanh((a - KNEE) / (1f - KNEE)))
        }
    }
}
