package com.sanabria.motointercom.core.audio

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Detector de voz por energía. El ruido de fondo es el mínimo del nivel en una ventana deslizante de
 * [BLOCKS] × [BLOCK_FRAMES] tramas (1,6 s): al hablar siempre hay huecos entre sílabas, y el viento o el motor, que no
 * paran, suben ese mínimo enseguida. Abre tras [ATTACK_FRAMES] tramas por encima del umbral y aguanta
 * [HANGOVER_FRAMES] tramas después de la última (no corta entre palabras).
 */
class Vad(private val frameSamples: Int = VoiceFormat.FRAME_SAMPLES) {

    /** 0 = hay que hablar fuerte para abrir; 1 = abre con poco. */
    var sensitivity = 0.5f

    var levelDb = SILENCE_DB
        private set
    var noiseDb = SILENCE_DB
        private set
    var open = false
        private set

    private val blockMins = FloatArray(BLOCKS) { 0f }
    private var blockMin = 0f
    private var blockFrames = 0
    private var blockIndex = 0
    private var above = 0
    private var hang = 0

    fun process(pcm: ShortArray): Boolean {
        var sum = 0.0
        for (i in 0 until frameSamples) {
            val s = pcm[i].toDouble()
            sum += s * s
        }
        val rms = sqrt(sum / frameSamples)
        levelDb = if (rms < 1.0) SILENCE_DB else (20.0 * log10(rms / 32768.0)).toFloat()

        blockMin = min(blockMin, levelDb)
        if (++blockFrames == BLOCK_FRAMES) {
            blockMins[blockIndex] = blockMin
            blockIndex = (blockIndex + 1) % BLOCKS
            blockMin = 0f
            blockFrames = 0
        }
        noiseDb = min(blockMins.min(), blockMin) + NOISE_BIAS_DB

        val threshold = max(noiseDb + 20f - 14f * sensitivity, MIN_THRESHOLD_DB)
        if (levelDb > threshold) {
            if (++above >= ATTACK_FRAMES) {
                open = true
                hang = HANGOVER_FRAMES
            }
        } else {
            above = 0
            if (open && --hang <= 0) open = false
        }
        return open
    }

    fun reset() {
        open = false
        above = 0
        hang = 0
        blockMins.fill(0f)
        blockMin = 0f
        blockFrames = 0
        levelDb = SILENCE_DB
        noiseDb = SILENCE_DB
    }

    companion object {
        const val SILENCE_DB = -96f
        const val MIN_THRESHOLD_DB = -50f
        const val ATTACK_FRAMES = 2
        const val HANGOVER_FRAMES = 20
        const val BLOCK_FRAMES = 10
        const val BLOCKS = 8

        /** El mínimo de un ruido que fluctúa queda por debajo de su media. */
        const val NOISE_BIAS_DB = 3f
    }
}
