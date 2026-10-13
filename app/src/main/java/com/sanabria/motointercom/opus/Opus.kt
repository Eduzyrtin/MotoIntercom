package com.sanabria.motointercom.opus

import com.sanabria.motointercom.core.audio.VoiceDecoder
import com.sanabria.motointercom.core.audio.VoiceEncoder
import com.sanabria.motointercom.core.audio.VoiceFormat

internal object OpusNative {
    init {
        System.loadLibrary("motointercom")
    }

    @JvmStatic external fun encoderCreate(sampleRate: Int, bitrate: Int): Long
    @JvmStatic external fun encoderSetBitrate(handle: Long, bitrate: Int)
    @JvmStatic external fun encode(handle: Long, pcm: ShortArray, frameSize: Int, out: ByteArray): Int
    @JvmStatic external fun encoderDestroy(handle: Long)
    @JvmStatic external fun decoderCreate(sampleRate: Int): Long
    @JvmStatic external fun decode(handle: Long, packet: ByteArray?, len: Int, pcm: ShortArray, frameSize: Int, fec: Boolean): Int
    @JvmStatic external fun decoderDestroy(handle: Long)
}

/** Opus en modo VoIP, mono, con FEC. Un objeto por hilo: libopus no es reentrante con el mismo estado. */
class OpusVoiceEncoder(bitrate: Int, private val sampleRate: Int = VoiceFormat.SAMPLE_RATE) : VoiceEncoder {
    private var handle = OpusNative.encoderCreate(sampleRate, bitrate).also { check(it != 0L) { "opus_encoder_create" } }
    private val frame = sampleRate * VoiceFormat.FRAME_MS / 1000

    override fun encode(pcm: ShortArray, out: ByteArray): Int = OpusNative.encode(handle, pcm, frame, out)

    override fun setBitrate(bps: Int) = OpusNative.encoderSetBitrate(handle, bps)

    override fun close() {
        OpusNative.encoderDestroy(handle)
        handle = 0
    }
}

class OpusVoiceDecoder(private val sampleRate: Int = VoiceFormat.SAMPLE_RATE) : VoiceDecoder {
    private var handle = OpusNative.decoderCreate(sampleRate).also { check(it != 0L) { "opus_decoder_create" } }
    private val frame = sampleRate * VoiceFormat.FRAME_MS / 1000

    override fun decode(packet: ByteArray, len: Int, pcm: ShortArray): Int =
        OpusNative.decode(handle, packet, len, pcm, frame, false)

    override fun decodeFec(next: ByteArray, len: Int, pcm: ShortArray): Int =
        OpusNative.decode(handle, next, len, pcm, frame, true)

    override fun conceal(pcm: ShortArray): Int = OpusNative.decode(handle, null, 0, pcm, frame, false)

    override fun close() {
        OpusNative.decoderDestroy(handle)
        handle = 0
    }
}
