package com.sanabria.motointercom.core

import com.sanabria.motointercom.core.protocol.AdvInfo
import com.sanabria.motointercom.core.protocol.Frame
import com.sanabria.motointercom.core.protocol.FrameAssembler
import com.sanabria.motointercom.core.protocol.FrameCodec
import com.sanabria.motointercom.core.protocol.MalformedFrameException
import com.sanabria.motointercom.core.protocol.Neighbor
import com.sanabria.motointercom.core.protocol.Seq
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProtocolTest {

    private fun roundTrip(f: Frame): Frame {
        val out = mutableListOf<Frame>()
        FrameAssembler.plain { out += it }.feed(FrameCodec.encode(f))
        assertEquals(1, out.size)
        return out[0]
    }

    @Test
    fun framesRoundTrip() {
        val nonce = ByteArray(16) { it.toByte() }
        val mac = ByteArray(16) { (100 + it).toByte() }
        val h = assertIs<Frame.Hello>(roundTrip(Frame.Hello(2, -5, 0x12345678, nonce, mac)))
        assertEquals(2, h.protocol)
        assertEquals(-5, h.nodeId)
        assertEquals(0x12345678, h.groupId)
        assertContentEquals(nonce, h.nonce)
        assertContentEquals(mac, h.mac)

        assertSame(Frame.Ready, roundTrip(Frame.Ready))
        assertEquals(Frame.Ping(123456), roundTrip(Frame.Ping(123456)))
        assertEquals(Frame.Pong(-1), roundTrip(Frame.Pong(-1)))
        val ls = Frame.LinkState(7, 65535, 3, "Ñandú", 120, listOf(Neighbor(1, 4), Neighbor(2, 255), Neighbor(-3, 9)))
        assertEquals(ls, roundTrip(ls))

        val a = assertIs<Frame.Audio>(roundTrip(Frame.Audio(-9, 77, -2, Frame.Audio.START, 2, byteArrayOf(1, 2, 3))))
        assertEquals(-9, a.origin)
        assertEquals(77, a.session)
        assertEquals(-2, a.counter)
        assertEquals(Frame.Audio.START, a.flags)
        assertEquals(2, a.hops)
        assertContentEquals(byteArrayOf(1, 2, 3), a.payload)
    }

    @Test
    fun assemblerHandlesSplitAndMergedReads() {
        val frames = listOf(Frame.Ping(1), Frame.LinkState(1, 2, 0, "x", 0, emptyList()), Frame.Pong(4))
        val stream = frames.map { FrameCodec.encode(it) }.reduce { a, b -> a + b }
        val out = mutableListOf<Frame>()
        val asm = FrameAssembler.plain { out += it }
        for (b in stream) asm.feed(byteArrayOf(b))
        assertEquals(frames, out)

        out.clear()
        asm.feed(stream)
        assertEquals(frames, out)
    }

    @Test
    fun unknownTypeIsSkippedAndBadLengthFails() {
        val out = mutableListOf<Frame>()
        val asm = FrameAssembler.plain { out += it }
        asm.feed(byteArrayOf(0, 3, 99, 1, 2) + FrameCodec.encode(Frame.Ping(5)))
        assertEquals(listOf<Frame>(Frame.Ping(5)), out)
        assertFailsWith<MalformedFrameException> { asm.feed(byteArrayOf(0, 0)) }
    }

    @Test
    fun namesAreClippedWithoutBreakingCharacters() {
        val clipped = FrameCodec.clipName("ñ".repeat(40))
        assertTrue(clipped.encodeToByteArray().size <= FrameCodec.MAX_NAME_BYTES)
        assertEquals("ñ".repeat(16), clipped)
    }

    @Test
    fun advInfoRoundTrip() {
        val info = AdvInfo(2, 0x1234ABCD, -77, 0x0081, 1)
        assertEquals(AdvInfo.SIZE, info.encode().size)
        assertEquals(info, AdvInfo.decode(info.encode()))
    }

    @Test
    fun sequenceArithmeticWraps() {
        assertEquals(0, Seq.next(65535))
        assertEquals(1, Seq.diff(0, 65535))
        assertEquals(-1, Seq.diff(65535, 0))
        assertTrue(Seq.isNewer(3, 65530))
    }
}
