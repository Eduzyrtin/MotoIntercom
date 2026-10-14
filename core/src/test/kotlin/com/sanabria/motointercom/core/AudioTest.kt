package com.sanabria.motointercom.core

import com.sanabria.motointercom.core.audio.Earcons
import com.sanabria.motointercom.core.audio.JitterBuffer
import com.sanabria.motointercom.core.audio.Mixer
import com.sanabria.motointercom.core.audio.TalkSender
import com.sanabria.motointercom.core.audio.Vad
import com.sanabria.motointercom.core.audio.VoiceFormat
import com.sanabria.motointercom.core.protocol.Frame
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioTest {

    private fun p(seq: Int) = byteArrayOf(seq.toByte())

    @Test
    fun jitterBufferWaitsForTargetThenPlaysInOrder() {
        val jb = JitterBuffer(minTarget = 2)
        jb.put(100, Frame.Audio.START, p(100), 0)
        assertIs<JitterBuffer.Out.Silence>(jb.pull(0))
        jb.put(101, 0, p(101), 20)
        assertContentEquals(p(100), assertIs<JitterBuffer.Out.Packet>(jb.pull(20)).payload)
        assertContentEquals(p(101), assertIs<JitterBuffer.Out.Packet>(jb.pull(40)).payload)
    }

    @Test
    fun missingFrameIsLostWithNextForFec() {
        val jb = JitterBuffer(minTarget = 2)
        jb.put(1, 0, p(1), 0)
        jb.put(3, 0, p(3), 0)
        assertIs<JitterBuffer.Out.Packet>(jb.pull(0))
        assertContentEquals(p(3), assertIs<JitterBuffer.Out.Lost>(jb.pull(20)).next)
        assertContentEquals(p(3), assertIs<JitterBuffer.Out.Packet>(jb.pull(40)).payload)
        assertEquals(1, jb.lost)
    }

    @Test
    fun endFlagStopsTheStream() {
        val jb = JitterBuffer(minTarget = 1)
        jb.put(7, Frame.Audio.START, p(7), 0)
        jb.put(8, Frame.Audio.END, p(8), 0)
        assertIs<JitterBuffer.Out.Packet>(jb.pull(0))
        assertIs<JitterBuffer.Out.Packet>(jb.pull(20))
        assertEquals(JitterBuffer.State.IDLE, jb.state)
        assertIs<JitterBuffer.Out.Silence>(jb.pull(40))
    }

    @Test
    fun underrunRaisesTargetAndStillPlaysLateFrame() {
        val jb = JitterBuffer(minTarget = 1)
        jb.put(1, 0, p(1), 0)
        assertIs<JitterBuffer.Out.Packet>(jb.pull(0))
        assertNull(assertIs<JitterBuffer.Out.Lost>(jb.pull(20)).next)
        assertEquals(2, jb.target)
        jb.put(2, 0, p(2), 50)
        jb.put(3, 0, p(3), 50)
        assertContentEquals(p(2), assertIs<JitterBuffer.Out.Packet>(jb.pull(60)).payload)
        assertEquals(0, jb.late)
    }

    @Test
    fun silentSenderGoesIdle() {
        val jb = JitterBuffer(minTarget = 1)
        jb.put(1, 0, p(1), 0)
        jb.pull(0)
        repeat(30) { jb.pull(20L * (it + 1)) }
        assertEquals(JitterBuffer.State.IDLE, jb.state)
    }

    @Test
    fun mixerLimitsWithoutWrapping() {
        val m = Mixer(4)
        val loud = ShortArray(4) { 30000 }
        m.begin()
        m.add(loud)
        m.add(loud)
        val out = ShortArray(4)
        assertTrue(m.end(1f, out))
        assertTrue(out.all { it in 25000..32767 })
        assertEquals(0.5f, Mixer.softClip(0.5f))
        assertTrue(abs(Mixer.softClip(-10f)) <= 1f)

        m.begin()
        assertFalse(m.end(1f, out))
        assertTrue(out.all { it == 0.toShort() })
    }

    private fun tone(amplitude: Double) =
        ShortArray(VoiceFormat.FRAME_SAMPLES) { (amplitude * sin(2 * PI * 300 * it / VoiceFormat.SAMPLE_RATE)).toInt().toShort() }

    private fun noise(amplitude: Int, seed: Int): ShortArray {
        var x = seed
        return ShortArray(VoiceFormat.FRAME_SAMPLES) {
            x = x * 1103515245 + 12345
            ((x ushr 16) % (2 * amplitude + 1) - amplitude).toShort()
        }
    }

    @Test
    fun vadOpensOnSpeechClosesAfterHangover() {
        val vad = Vad()
        repeat(100) { assertFalse(vad.process(noise(100, it))) }
        assertFalse(vad.process(tone(8000.0)))
        assertTrue(vad.process(tone(8000.0)))
        repeat(Vad.HANGOVER_FRAMES - 1) { assertTrue(vad.process(noise(100, it))) }
        assertFalse(vad.process(noise(100, 999)))
    }

    @Test
    fun vadAdaptsToSteadyWind() {
        val vad = Vad()
        repeat(100) { vad.process(noise(100, it)) }
        // Empieza el viento: abre al principio, pero tras la ventana de 1,6 s (y la cola) ya no cuenta como voz
        var openedAtStart = false
        repeat(150) {
            vad.process(noise(3000, 1000 + it))
            if (it < 5) openedAtStart = openedAtStart || vad.open
        }
        assertTrue(openedAtStart)
        assertFalse(vad.open)
    }

    @Test
    fun talkSenderSendsPreRollAndMarksBursts() {
        val sent = mutableListOf<Triple<Int, Int, Int>>()
        val ts = TalkSender(preRoll = 2) { seq, flags, payload -> sent += Triple(seq, flags, payload[0].toInt()) }
        for (i in 1..5) ts.onFrame(byteArrayOf(i.toByte()), open = false)
        assertTrue(sent.isEmpty())
        ts.onFrame(byteArrayOf(6), open = true)
        ts.onFrame(byteArrayOf(7), open = true)
        ts.onFrame(byteArrayOf(8), open = false)
        ts.onFrame(byteArrayOf(9), open = false)

        assertEquals(listOf(4, 5, 6, 7, 8), sent.map { it.third })
        assertEquals(listOf(Frame.Audio.START, 0, 0, 0, Frame.Audio.END), sent.map { it.second })
        assertEquals(listOf(0, 1, 2, 3, 4), sent.map { it.first })
    }

    @Test
    fun earconsPlayInOrderAndEnd() {
        val e = Earcons()
        val frame = ShortArray(VoiceFormat.FRAME_SAMPLES)
        assertFalse(e.next(frame))
        e.play(Earcons.Kind.MIC_ON)
        e.play(Earcons.Kind.JOINED)
        var frames = 0
        var peak = 0
        while (e.next(frame)) {
            frames++
            peak = maxOf(peak, frame.maxOf { abs(it.toInt()) })
        }
        // 90 ms + 90 + 40 + 90 = 310 ms → 16 tramas de 20 ms
        assertEquals(16, frames)
        assertTrue(peak in 5000..9000)
    }
}
