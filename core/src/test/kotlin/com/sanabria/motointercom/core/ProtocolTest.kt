package com.sanabria.motointercom.core

import com.sanabria.motointercom.core.protocol.AdvInfo
import com.sanabria.motointercom.core.protocol.Frame
import com.sanabria.motointercom.core.protocol.FrameAssembler
import com.sanabria.motointercom.core.protocol.FrameCodec
import com.sanabria.motointercom.core.protocol.GroupId
import com.sanabria.motointercom.core.protocol.MalformedFrameException
import com.sanabria.motointercom.core.protocol.Seq
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProtocolTest {

    private fun roundTrip(f: Frame): Frame {
        val out = mutableListOf<Frame>()
        FrameAssembler { out += it }.feed(FrameCodec.encode(f))
        assertEquals(1, out.size)
        return out[0]
    }

    @Test
    fun framesRoundTrip() {
        assertEquals(Frame.Hello(1, -5, 0x12345678, "Edu"), roundTrip(Frame.Hello(1, -5, 0x12345678, "Edu")))
        assertEquals(Frame.Ping(123456), roundTrip(Frame.Ping(123456)))
        assertEquals(Frame.Pong(-1), roundTrip(Frame.Pong(-1)))
        val ls = Frame.LinkState(7, 65535, 3, "Ñandú", listOf(1, 2, -3))
        assertEquals(ls, roundTrip(ls))

        val a = assertIs<Frame.Audio>(roundTrip(Frame.Audio(-9, 65535, Frame.Audio.START, 2, byteArrayOf(1, 2, 3))))
        assertEquals(-9, a.origin)
        assertEquals(65535, a.seq)
        assertEquals(Frame.Audio.START, a.flags)
        assertEquals(2, a.hops)
        assertContentEquals(byteArrayOf(1, 2, 3), a.payload)
    }

    @Test
    fun assemblerHandlesSplitAndMergedReads() {
        val frames = listOf(Frame.Ping(1), Frame.Hello(1, 2, 3, "x"), Frame.Pong(4))
        val stream = frames.map { FrameCodec.encode(it) }.reduce { a, b -> a + b }
        val out = mutableListOf<Frame>()
        val asm = FrameAssembler { out += it }
        for (b in stream) asm.feed(byteArrayOf(b))
        assertEquals(frames, out)

        out.clear()
        asm.feed(stream)
        assertEquals(frames, out)
    }

    @Test
    fun unknownTypeIsSkippedAndBadLengthFails() {
        val out = mutableListOf<Frame>()
        val asm = FrameAssembler { out += it }
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
    fun advInfoAndGroupId() {
        val info = AdvInfo(1, GroupId.of("moto"), -77, 0x0081, 1)
        assertEquals(AdvInfo.SIZE, info.encode().size)
        assertEquals(info, AdvInfo.decode(info.encode()))
        assertEquals(GroupId.of(" Moto "), GroupId.of("MOTO"))
        assertTrue(GroupId.of("a") != GroupId.of("b"))
    }

    @Test
    fun sequenceArithmeticWraps() {
        assertEquals(0, Seq.next(65535))
        assertEquals(1, Seq.diff(0, 65535))
        assertEquals(-1, Seq.diff(65535, 0))
        assertTrue(Seq.isNewer(3, 65530))
    }
}
