package com.sanabria.motointercom.audio

import android.content.Context
import android.media.AudioManager
import com.sanabria.motointercom.core.audio.VolumeLink
import com.sanabria.motointercom.engine.AudioMode
import com.sanabria.motointercom.engine.VoiceOutput
import kotlin.math.roundToInt

/**
 * Lleva los niveles de [VolumeLink] (maestro M, voz V, multimedia U) a los volúmenes del sistema y a la ganancia de las
 * voces, según por dónde va el audio. La idea es que lo que se oye sea voz ≈ V y música ≈ U.
 *
 * - Casco (HFP): todo va por SCO y el casco aplica el volumen de llamada a todo, así que llamada = M,
 *   multimedia = 1 + U − M y voces = ganancia(V − M).
 * - Mezcla, voces como multimedia: voces y música comparten el volumen multimedia, así que multimedia = U y
 *   voces = ganancia(V − U), como mucho +12 dB. Si la música va muy baja, las voces no pueden subir tanto.
 * - Mezcla, voces como llamada: multimedia = U y llamada = V, sin ganancia.
 *
 * Los botones de volumen del móvil o del casco mueven el canal que manda en cada modo (llamada en HFP, multimedia en
 * mezcla). [pollButtons] detecta ese cambio y lo convierte en un movimiento del maestro: suben o bajan voz y música a la par.
 */
class VolumeOutputs(context: Context) {
    private val am = context.getSystemService(AudioManager::class.java)
    private var buttonStream = -1
    private var lastButtonIndex = -1

    /** Aplica los niveles y devuelve la ganancia de software para las voces. */
    fun apply(link: VolumeLink, mode: AudioMode, voiceOutput: VoiceOutput): Float {
        val v = link.voice
        val u = link.media
        val m = link.master
        val gain = when {
            mode == AudioMode.HELMET -> {
                buttonStream = AudioManager.STREAM_VOICE_CALL
                setLevel(AudioManager.STREAM_VOICE_CALL, m)
                setLevel(AudioManager.STREAM_MUSIC, 1f + u - m)
                voiceGain(v, v - m)
            }
            voiceOutput == VoiceOutput.CALL -> {
                buttonStream = AudioManager.STREAM_MUSIC
                setLevel(AudioManager.STREAM_MUSIC, u)
                setLevel(AudioManager.STREAM_VOICE_CALL, v)
                if (v > 0f) 1f else 0f
            }
            else -> {
                buttonStream = AudioManager.STREAM_MUSIC
                // Con la multimedia a 0 las voces tampoco se oirían: como poco un paso si hay voz
                val actual = setLevel(AudioManager.STREAM_MUSIC, u, minIndex = if (v > 0f) 1 else 0)
                voiceGain(v, v - actual).coerceAtMost(MAX_MIX_BOOST)
            }
        }
        lastButtonIndex = am.getStreamVolume(buttonStream)
        return gain
    }

    /** Si los botones han movido el canal que manda, mueve el maestro lo mismo. Devuelve true si hay que volver a aplicar. */
    fun pollButtons(link: VolumeLink): Boolean {
        if (buttonStream < 0) return false
        val idx = am.getStreamVolume(buttonStream)
        if (idx == lastButtonIndex) return false
        link.nudgeMaster((idx - lastButtonIndex).toFloat() / am.getStreamMaxVolume(buttonStream))
        lastButtonIndex = idx
        return true
    }

    fun stop() {
        buttonStream = -1
        lastButtonIndex = -1
    }

    /** Pone el canal al nivel más cercano y devuelve el nivel que ha quedado de verdad (los canales van por pasos). */
    private fun setLevel(stream: Int, level: Float, minIndex: Int = 0): Float {
        val max = am.getStreamMaxVolume(stream)
        val min = maxOf(am.getStreamMinVolume(stream), minIndex)
        val idx = (level.coerceIn(0f, 1f) * max).roundToInt().coerceIn(min, max)
        // Con No molestar algunos canales no se pueden tocar
        runCatching { am.setStreamVolume(stream, idx, 0) }
        return am.getStreamVolume(stream).toFloat() / max
    }

    private fun voiceGain(level: Float, diff: Float) = if (level <= 0f) 0f else VolumeLink.gain(diff)

    private companion object {
        /** +12 dB: lo que da de sí el limitador sin que las voces suenen aplastadas. */
        const val MAX_MIX_BOOST = 4f
    }
}
