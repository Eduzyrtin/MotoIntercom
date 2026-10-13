package com.sanabria.motointercom.core.protocol

class MalformedFrameException(message: String) : Exception(message)

/** Escritura big-endian sobre un ByteArray que crece. Sin java.nio: el núcleo tiene que compilar también para iOS. */
class ByteWriter(initial: Int = 64) {
    private var buf = ByteArray(initial)
    var size = 0
        private set

    private fun ensure(n: Int) {
        if (size + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + n))
    }

    fun u8(v: Int) {
        ensure(1)
        buf[size++] = v.toByte()
    }

    fun u16(v: Int) {
        ensure(2)
        buf[size++] = (v ushr 8).toByte()
        buf[size++] = v.toByte()
    }

    fun u32(v: Int) {
        ensure(4)
        buf[size++] = (v ushr 24).toByte()
        buf[size++] = (v ushr 16).toByte()
        buf[size++] = (v ushr 8).toByte()
        buf[size++] = v.toByte()
    }

    fun bytes(b: ByteArray, off: Int = 0, len: Int = b.size - off) {
        ensure(len)
        b.copyInto(buf, size, off, off + len)
        size += len
    }

    fun setU16(pos: Int, v: Int) {
        buf[pos] = (v ushr 8).toByte()
        buf[pos + 1] = v.toByte()
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)
}

/** Lectura big-endian de [buf] entre [pos] y [end]. Lanza [MalformedFrameException] si se sale del rango. */
class ByteReader(private val buf: ByteArray, private var pos: Int, private val end: Int) {
    val remaining: Int get() = end - pos

    private fun need(n: Int) {
        if (pos + n > end) throw MalformedFrameException("trama corta: faltan ${pos + n - end} bytes")
    }

    fun u8(): Int {
        need(1)
        return buf[pos++].toInt() and 0xFF
    }

    fun u16(): Int {
        need(2)
        val v = ((buf[pos].toInt() and 0xFF) shl 8) or (buf[pos + 1].toInt() and 0xFF)
        pos += 2
        return v
    }

    fun u32(): Int {
        need(4)
        val v = ((buf[pos].toInt() and 0xFF) shl 24) or ((buf[pos + 1].toInt() and 0xFF) shl 16) or
            ((buf[pos + 2].toInt() and 0xFF) shl 8) or (buf[pos + 3].toInt() and 0xFF)
        pos += 4
        return v
    }

    fun bytes(n: Int): ByteArray {
        need(n)
        val out = buf.copyOfRange(pos, pos + n)
        pos += n
        return out
    }
}

/** Números de secuencia de 16 bits con vuelta (RFC 1982 simplificado). */
object Seq {
    const val MASK = 0xFFFF

    fun next(s: Int): Int = (s + 1) and MASK

    /** a - b con signo, en el anillo de 16 bits: positivo si a va por delante de b. */
    fun diff(a: Int, b: Int): Int {
        val d = (a - b) and MASK
        return if (d >= 0x8000) d - 0x10000 else d
    }

    fun isNewer(a: Int, b: Int): Boolean = diff(a, b) > 0
}
