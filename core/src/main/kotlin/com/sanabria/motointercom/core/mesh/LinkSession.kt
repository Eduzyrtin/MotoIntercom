package com.sanabria.motointercom.core.mesh

import com.sanabria.motointercom.core.crypto.CryptoProvider
import com.sanabria.motointercom.core.crypto.GroupKeys
import com.sanabria.motointercom.core.crypto.LinkCipher
import com.sanabria.motointercom.core.protocol.Frame
import com.sanabria.motointercom.core.protocol.FrameCodec
import kotlin.concurrent.Volatile

/**
 * Saludo y cifrado de un enlace, igual para cualquier transporte y plataforma.
 *
 * 1. Cada extremo envía su [Frame.Hello] en claro: id, grupo, nonce aleatorio y MAC con la clave de saludo del grupo.
 * 2. Al recibir el del otro se comprueban grupo, versión y MAC, y se deriva la clave del enlace con los dos nonces.
 *    Desde ahí todo va cifrado; lo primero, [Frame.Ready].
 * 3. El enlace vale ([Event.Up]) cuando llega el Ready del otro: ha demostrado tener la clave. Un Hello repetido por
 *    alguien de fuera no pasa de aquí.
 *
 * [onContent] y la lectura van en un hilo y [seal] en otro: el cifrador se publica con @Volatile.
 */
class LinkSession(
    private val crypto: CryptoProvider,
    private val keys: GroupKeys,
    private val selfId: Int,
    private val initiator: Boolean,
) {
    enum class State { AWAIT_HELLO, AWAIT_READY, UP, FAILED }

    sealed class Event {
        /** El otro es del grupo: ya se sabe quién es y hay que enviarle [ready]. */
        data class PeerHello(val nodeId: Int) : Event()

        object Up : Event()

        class Received(val frame: Frame) : Event()

        class Failed(val reason: Reason) : Event()
    }

    enum class Reason { NOT_HELLO, PROTOCOL, OTHER_GROUP, SELF, BAD_MAC, NOT_READY, DECRYPT }

    @Volatile
    var state = State.AWAIT_HELLO
        private set

    @Volatile
    var peerId = 0
        private set

    private val localNonce = crypto.randomBytes(FrameCodec.NONCE_BYTES)

    @Volatile
    private var cipher: LinkCipher? = null

    /** La primera trama que se envía, en claro y con su longitud. */
    fun hello(): ByteArray = FrameCodec.encode(
        Frame.Hello(
            FrameCodec.PROTOCOL, selfId, keys.advId, localNonce,
            keys.helloMac(FrameCodec.PROTOCOL, selfId, keys.advId, localNonce),
        ),
    )

    /** Cifra un contenido (tipo + cuerpo) para este enlace y le pone la longitud. Solo tras [Event.PeerHello]. */
    fun seal(content: ByteArray): ByteArray {
        val c = checkNotNull(cipher) { "enlace sin saludar" }
        return FrameCodec.wrap(c.seal(content))
    }

    /** La primera trama cifrada; se envía en cuanto llega [Event.PeerHello]. */
    fun ready(): ByteArray = seal(FrameCodec.encodeBody(Frame.Ready))

    /** Procesa el contenido de una trama recibida (sin la longitud). */
    fun onContent(buf: ByteArray, off: Int, len: Int): Event? = when (state) {
        State.AWAIT_HELLO -> onHello(buf, off, len)
        State.AWAIT_READY -> {
            val f = decrypt(buf, off, len)
            when {
                f == null -> fail(Reason.DECRYPT)
                f !is Frame.Ready -> fail(Reason.NOT_READY)
                else -> {
                    state = State.UP
                    Event.Up
                }
            }
        }
        State.UP -> {
            val c = cipher!!
            val plain = c.open(buf, off, len)
            if (plain == null) fail(Reason.DECRYPT) else FrameCodec.decodeBody(plain)?.let { Event.Received(it) }
        }
        State.FAILED -> null
    }

    private fun onHello(buf: ByteArray, off: Int, len: Int): Event {
        val f = FrameCodec.decodeBody(buf, off, len) as? Frame.Hello ?: return fail(Reason.NOT_HELLO)
        if (f.protocol != FrameCodec.PROTOCOL) return fail(Reason.PROTOCOL)
        if (f.groupId != keys.advId) return fail(Reason.OTHER_GROUP)
        if (f.nodeId == selfId) return fail(Reason.SELF)
        val mac = keys.helloMac(f.protocol, f.nodeId, f.groupId, f.nonce)
        if (!GroupKeys.constantTimeEquals(mac, f.mac)) return fail(Reason.BAD_MAC)
        peerId = f.nodeId
        val key = if (initiator) keys.linkKey(localNonce, f.nonce) else keys.linkKey(f.nonce, localNonce)
        cipher = LinkCipher(crypto, key, initiator)
        state = State.AWAIT_READY
        return Event.PeerHello(f.nodeId)
    }

    private fun decrypt(buf: ByteArray, off: Int, len: Int): Frame? =
        cipher?.open(buf, off, len)?.let { FrameCodec.decodeBody(it) }

    private fun fail(reason: Reason): Event {
        state = State.FAILED
        return Event.Failed(reason)
    }
}
