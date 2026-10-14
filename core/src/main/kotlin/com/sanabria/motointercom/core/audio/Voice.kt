package com.sanabria.motointercom.core.audio

/**
 * Formato de la voz en todo el sistema: mono, 48 kHz (banda completa, la frecuencia nativa de A2DP y del mezclador de
 * Android: sin remuestreo en el modo de alta calidad), tramas de 20 ms. Opus decide el ancho de banda según el bitrate.
 */
object VoiceFormat {
    const val SAMPLE_RATE = 48_000
    const val FRAME_MS = 20
    const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000
    const val MAX_PACKET = 400
}

/** Códec de voz (Opus). La implementación es de cada plataforma: JNI en Android, libopus enlazada en iOS. */
interface VoiceEncoder {
    /** Codifica una trama de [VoiceFormat.FRAME_SAMPLES] muestras en [out]; devuelve los bytes escritos o < 0 si falla. */
    fun encode(pcm: ShortArray, out: ByteArray): Int

    fun setBitrate(bps: Int)

    fun close()
}

interface VoiceDecoder {
    /** Decodifica [len] bytes de [packet] en [pcm]; devuelve muestras o < 0. */
    fun decode(packet: ByteArray, len: Int, pcm: ShortArray): Int

    /** Reconstruye la trama perdida con la redundancia (FEC) que trae la siguiente, [next]. */
    fun decodeFec(next: ByteArray, len: Int, pcm: ShortArray): Int

    /** Inventa una trama plausible cuando no hay nada (ocultación de pérdidas). */
    fun conceal(pcm: ShortArray): Int

    fun close()
}
