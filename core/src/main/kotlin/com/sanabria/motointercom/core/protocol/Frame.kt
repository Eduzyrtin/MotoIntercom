package com.sanabria.motointercom.core.protocol

/**
 * Tramas que viajan por un enlace (L2CAP hoy; cualquier transporte de bytes mañana).
 *
 * Formato en el cable: `[u16 longitud][u8 tipo][cuerpo]`, big-endian, donde longitud = 1 + tamaño del cuerpo.
 * El enlace es un flujo de bytes (iOS expone L2CAP como NSStream), por eso cada trama lleva su longitud.
 * Un tipo desconocido se salta sin cortar el enlace: versiones futuras pueden añadir tramas.
 */
sealed class Frame {
    /** Primera trama en cada sentido de un enlace nuevo. Si el grupo o la versión no casan, se corta. */
    data class Hello(val protocol: Int, val nodeId: Int, val groupId: Int, val name: String) : Frame()

    /**
     * Voz de [origin]. [seq] cuenta tramas enviadas (16 bits); [hops] cuántas veces se ha reenviado.
     * [payload] es un paquete Opus de 20 ms.
     */
    class Audio(val origin: Int, val seq: Int, val flags: Int, val hops: Int, val payload: ByteArray) : Frame() {
        fun nextHop() = Audio(origin, seq, flags, hops + 1, payload)

        companion object {
            /** Primera trama de una ráfaga de voz. */
            const val START = 0x01

            /** Última trama de una ráfaga: el receptor deja de reproducir sin esperar al tiempo de espera. */
            const val END = 0x02
        }
    }

    /** Estado de enlaces de [origin] (sus vecinos directos). Se inunda por la malla para que todos calculen las rutas. */
    data class LinkState(val origin: Int, val version: Int, val hops: Int, val name: String, val neighbors: List<Int>) : Frame()

    /** Medida de ida y vuelta de un enlace: el otro extremo devuelve [time] en un [Pong]. */
    data class Ping(val time: Int) : Frame()

    data class Pong(val time: Int) : Frame()
}

object FrameCodec {
    const val PROTOCOL = 1
    const val MAX_FRAME = 2048
    const val MAX_NAME_BYTES = 32
    const val MAX_NEIGHBORS = 32

    private const val HELLO = 1
    private const val AUDIO = 2
    private const val LINK_STATE = 3
    private const val PING = 4
    private const val PONG = 5

    fun encode(f: Frame): ByteArray {
        val w = ByteWriter(16 + if (f is Frame.Audio) f.payload.size else 48)
        w.u16(0)
        when (f) {
            is Frame.Hello -> {
                w.u8(HELLO)
                w.u8(f.protocol)
                w.u32(f.nodeId)
                w.u32(f.groupId)
                name(w, f.name)
            }
            is Frame.Audio -> {
                w.u8(AUDIO)
                w.u32(f.origin)
                w.u16(f.seq)
                w.u8(f.flags)
                w.u8(f.hops)
                w.bytes(f.payload)
            }
            is Frame.LinkState -> {
                w.u8(LINK_STATE)
                w.u32(f.origin)
                w.u16(f.version)
                w.u8(f.hops)
                name(w, f.name)
                val n = minOf(f.neighbors.size, MAX_NEIGHBORS)
                w.u8(n)
                for (i in 0 until n) w.u32(f.neighbors[i])
            }
            is Frame.Ping -> {
                w.u8(PING)
                w.u32(f.time)
            }
            is Frame.Pong -> {
                w.u8(PONG)
                w.u32(f.time)
            }
        }
        val len = w.size - 2
        if (len > MAX_FRAME) throw MalformedFrameException("trama de $len bytes")
        w.setU16(0, len)
        return w.toByteArray()
    }

    /** Decodifica tipo + cuerpo ([len] bytes desde [off]). Devuelve null si el tipo es desconocido. */
    fun decode(buf: ByteArray, off: Int, len: Int): Frame? {
        val r = ByteReader(buf, off, off + len)
        return when (r.u8()) {
            HELLO -> Frame.Hello(r.u8(), r.u32(), r.u32(), name(r))
            AUDIO -> {
                val origin = r.u32()
                val seq = r.u16()
                val flags = r.u8()
                val hops = r.u8()
                Frame.Audio(origin, seq, flags, hops, r.bytes(r.remaining))
            }
            LINK_STATE -> {
                val origin = r.u32()
                val version = r.u16()
                val hops = r.u8()
                val name = name(r)
                val n = r.u8()
                Frame.LinkState(origin, version, hops, name, List(n) { r.u32() })
            }
            PING -> Frame.Ping(r.u32())
            PONG -> Frame.Pong(r.u32())
            else -> null
        }
    }

    /** Recorta a [MAX_NAME_BYTES] bytes UTF-8 sin partir un carácter. */
    fun clipName(name: String): String {
        var s = name.trim()
        while (s.encodeToByteArray().size > MAX_NAME_BYTES) s = s.dropLast(1)
        return s
    }

    private fun name(w: ByteWriter, name: String) {
        val b = clipName(name).encodeToByteArray()
        w.u8(b.size)
        w.bytes(b)
    }

    private fun name(r: ByteReader): String = r.bytes(r.u8()).decodeToString()
}

/**
 * Reconstruye tramas a partir de trozos de un flujo de bytes (una lectura puede traer media trama o varias).
 * Lanza [MalformedFrameException] si la longitud es imposible: el enlace está corrupto y hay que cortarlo.
 */
class FrameAssembler(private val onFrame: (Frame) -> Unit) {
    private var buf = ByteArray(4096)
    private var len = 0

    fun feed(data: ByteArray, off: Int = 0, n: Int = data.size - off) {
        if (len + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + n))
        data.copyInto(buf, len, off, off + n)
        len += n
        var pos = 0
        while (len - pos >= 2) {
            val frameLen = ((buf[pos].toInt() and 0xFF) shl 8) or (buf[pos + 1].toInt() and 0xFF)
            if (frameLen == 0 || frameLen > FrameCodec.MAX_FRAME) throw MalformedFrameException("longitud $frameLen")
            if (len - pos < 2 + frameLen) break
            FrameCodec.decode(buf, pos + 2, frameLen)?.let(onFrame)
            pos += 2 + frameLen
        }
        if (pos > 0) {
            buf.copyInto(buf, 0, pos, len)
            len -= pos
        }
    }
}
