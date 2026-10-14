package com.sanabria.motointercom.core.audio

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Avisos sonoros cortos que se mezclan con las voces: en moto no se mira la pantalla.
 * - Micro abierto / cerrado (botón del casco): un pitido agudo o grave.
 * - Alguien entra en el grupo / se pierde: dos notas subiendo o bajando.
 *
 * [next] da una trama cada 20 ms desde la reproducción. No es seguro entre hilos: quien lo use lo protege.
 */
class Earcons(private val sampleRate: Int = VoiceFormat.SAMPLE_RATE) {

    enum class Kind { MIC_ON, MIC_OFF, JOINED, LEFT }

    private var pending = ShortArray(0)
    private var pos = 0

    fun play(kind: Kind) {
        val tones = when (kind) {
            Kind.MIC_ON -> listOf(1320.0)
            Kind.MIC_OFF -> listOf(660.0)
            Kind.JOINED -> listOf(660.0, 990.0)
            Kind.LEFT -> listOf(990.0, 660.0)
        }
        val pcm = tones.map { tone(it, TONE_MS) }.reduce { a, b -> a + silence(GAP_MS) + b }
        // Se encadena tras lo que quede por sonar
        pending = pending.copyOfRange(pos, pending.size) + pcm
        pos = 0
    }

    /** Copia en [out] la siguiente trama de aviso. Devuelve false si no hay nada que sonar. */
    fun next(out: ShortArray): Boolean {
        if (pos >= pending.size) return false
        val n = min(out.size, pending.size - pos)
        pending.copyInto(out, 0, pos, pos + n)
        out.fill(0, n, out.size)
        pos += n
        return true
    }

    private fun tone(freq: Double, ms: Int): ShortArray {
        val n = sampleRate * ms / 1000
        val fade = sampleRate * FADE_MS / 1000
        return ShortArray(n) { i ->
            val env = min(1.0, min(i, n - 1 - i).toDouble() / fade)
            (AMPLITUDE * env * sin(2 * PI * freq * i / sampleRate)).toInt().toShort()
        }
    }

    private fun silence(ms: Int) = ShortArray(sampleRate * ms / 1000)

    private companion object {
        const val TONE_MS = 90
        const val GAP_MS = 40
        const val FADE_MS = 8
        const val AMPLITUDE = 9000.0
    }
}
