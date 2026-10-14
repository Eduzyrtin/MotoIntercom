package com.sanabria.motointercom.core

import com.sanabria.motointercom.core.crypto.Group
import com.sanabria.motointercom.core.crypto.GroupInvite
import com.sanabria.motointercom.core.crypto.GroupKeys
import com.sanabria.motointercom.core.mesh.LinkSession
import com.sanabria.motointercom.core.protocol.Frame
import com.sanabria.motointercom.core.protocol.FrameAssembler
import com.sanabria.motointercom.core.protocol.FrameCodec
import com.sanabria.motointercom.core.protocol.Neighbor
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CryptoTest {
    private val crypto = JvmCrypto()
    private val groupKey = crypto.randomBytes(32)

    /** Dos extremos de un enlace unidos por dos colas ordenadas, como un canal L2CAP. */
    private inner class Pair(keyA: ByteArray = groupKey, keyB: ByteArray = groupKey) {
        val a = LinkSession(crypto, GroupKeys(crypto, keyA), selfId = 1, initiator = true)
        val b = LinkSession(crypto, GroupKeys(crypto, keyB), selfId = 2, initiator = false)
        val eventsA = mutableListOf<LinkSession.Event>()
        val eventsB = mutableListOf<LinkSession.Event>()
        private val toA = ArrayDeque<ByteArray>()
        private val toB = ArrayDeque<ByteArray>()
        private val asmA = FrameAssembler { buf, off, len ->
            a.onContent(buf, off, len)?.let {
                eventsA += it
                if (it is LinkSession.Event.PeerHello) toB.addLast(a.ready())
            }
        }
        private val asmB = FrameAssembler { buf, off, len ->
            b.onContent(buf, off, len)?.let {
                eventsB += it
                if (it is LinkSession.Event.PeerHello) toA.addLast(b.ready())
            }
        }

        fun send(bytes: ByteArray, toB: Boolean) {
            (if (toB) this.toB else toA).addLast(bytes)
            pump()
        }

        private fun pump() {
            while (toA.isNotEmpty() || toB.isNotEmpty()) {
                toB.removeFirstOrNull()?.let { asmB.feed(it) }
                toA.removeFirstOrNull()?.let { asmA.feed(it) }
            }
        }

        fun handshake() {
            toB.addLast(a.hello())
            toA.addLast(b.hello())
            pump()
        }
    }

    @Test
    fun handshakeAndEncryptedFrames() {
        val p = Pair()
        p.handshake()
        assertEquals(LinkSession.State.UP, p.a.state)
        assertEquals(LinkSession.State.UP, p.b.state)
        assertEquals(2, p.a.peerId)
        assertEquals(1, p.b.peerId)

        val ls = Frame.LinkState(1, 5, 0, "Edu", 80, listOf(Neighbor(2, 4)))
        p.send(p.a.seal(FrameCodec.encodeBody(ls)), toB = true)
        p.send(p.b.seal(FrameCodec.encodeBody(Frame.Ping(9))), toB = false)
        assertEquals(ls, assertIs<LinkSession.Event.Received>(p.eventsB.last()).frame)
        assertEquals(Frame.Ping(9), assertIs<LinkSession.Event.Received>(p.eventsA.last()).frame)
    }

    @Test
    fun otherGroupIsRejected() {
        val p = Pair(keyB = crypto.randomBytes(32))
        p.handshake()
        assertEquals(LinkSession.State.FAILED, p.a.state)
        assertEquals(LinkSession.Reason.OTHER_GROUP, assertIs<LinkSession.Event.Failed>(p.eventsA.single()).reason)
    }

    @Test
    fun forgedHelloWithoutKeyNeverComesUp() {
        // Alguien de fuera copia un Hello válido (mismo grupo y MAC) pero no tiene la clave: no puede enviar el Ready
        val p = Pair()
        val victim = p.b
        val captured = p.a.hello()
        val events = mutableListOf<LinkSession.Event>()
        FrameAssembler { buf, off, len -> victim.onContent(buf, off, len)?.let { events += it } }.apply {
            feed(captured)
            feed(FrameCodec.wrap(ByteArray(40) { 7 }))
        }
        assertIs<LinkSession.Event.PeerHello>(events[0])
        assertEquals(LinkSession.Reason.DECRYPT, assertIs<LinkSession.Event.Failed>(events[1]).reason)
        assertEquals(LinkSession.State.FAILED, victim.state)
    }

    @Test
    fun tamperedFrameIsRejected() {
        val p = Pair()
        p.handshake()
        val sealed = p.a.seal(FrameCodec.encodeBody(Frame.Ping(1)))
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 1).toByte()
        p.send(sealed, toB = true)
        assertEquals(LinkSession.Reason.DECRYPT, assertIs<LinkSession.Event.Failed>(p.eventsB.last()).reason)
    }

    @Test
    fun keysAreIndependentPerUseAndGroup() {
        val k1 = GroupKeys(crypto, groupKey)
        val k2 = GroupKeys(crypto, crypto.randomBytes(32))
        assertNotEquals(k1.advId, k2.advId)
        val n1 = crypto.randomBytes(16)
        val n2 = crypto.randomBytes(16)
        assertTrue(!k1.linkKey(n1, n2).contentEquals(k1.linkKey(n2, n1)))
        assertNull(crypto.open(k1.linkKey(n1, n2), ByteArray(12), crypto.seal(k2.linkKey(n1, n2), ByteArray(12), byteArrayOf(1)), 0, 17))
    }

    @Test
    fun inviteRoundTripAndParsingFromText() {
        val g = Group("Ruta del Cid ñ&=", groupKey)
        val uri = GroupInvite.toUri(g)
        assertTrue(uri.startsWith("motointercom://join?v=1&n="))
        assertEquals(g, GroupInvite.parse(uri))
        assertEquals(g, GroupInvite.parse("Únete a mi grupo: $uri\nNos vemos"))
        assertNull(GroupInvite.parse("motointercom://join?v=1&n=x&k=AAAA"))
        assertNull(GroupInvite.parse("hola"))
        assertContentEquals(groupKey, GroupInvite.fromBase64Url(GroupInvite.base64Url(groupKey)))
    }
}
