package com.sanabria.motointercom.core.crypto

import kotlin.io.encoding.Base64

/** Un grupo: nombre para mostrar y clave secreta. Dos grupos con el mismo nombre y distinta clave no se ven. */
class Group(val name: String, val key: ByteArray) {
    override fun equals(other: Any?) = other is Group && other.name == name && other.key.contentEquals(key)

    override fun hashCode() = 31 * name.hashCode() + key.contentHashCode()
}

/**
 * Invitación a un grupo: `motointercom://join?v=1&n=<nombre>&k=<clave base64url>`. Va en el QR y se puede compartir
 * como texto. [parse] la encuentra aunque venga dentro de un mensaje más largo.
 */
object GroupInvite {
    private const val PREFIX = "motointercom://join?"

    fun toUri(group: Group): String = PREFIX + "v=1&n=" + percentEncode(group.name) + "&k=" + base64Url(group.key)

    fun parse(text: String): Group? {
        val start = text.indexOf(PREFIX)
        if (start < 0) return null
        val query = text.substring(start + PREFIX.length).takeWhile { !it.isWhitespace() }
        val params = query.split('&').mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
        }.toMap()
        if (params["v"] != "1") return null
        val name = params["n"]?.let { percentDecode(it) } ?: return null
        val key = params["k"]?.let { fromBase64Url(it) } ?: return null
        if (key.size != GroupKeys.KEY_BYTES || name.isBlank()) return null
        return Group(name, key)
    }

    fun base64Url(b: ByteArray): String = Base64.UrlSafe.encode(b).trimEnd('=')

    fun fromBase64Url(s: String): ByteArray? = runCatching {
        Base64.UrlSafe.decode(s + "=".repeat((4 - s.length % 4) % 4))
    }.getOrNull()

    private fun percentEncode(s: String): String = buildString {
        for (b in s.encodeToByteArray()) {
            val c = b.toInt() and 0xFF
            if (c.toChar().isLetterOrDigit() && c < 0x80 || c.toChar() in "-._~") {
                append(c.toChar())
            } else {
                append('%')
                append(HEX[c shr 4])
                append(HEX[c and 0x0F])
            }
        }
    }

    private fun percentDecode(s: String): String? {
        val out = ArrayList<Byte>(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%') {
                if (i + 2 >= s.length) return null
                val v = s.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
                out += v.toByte()
                i += 3
            } else {
                // Si alguien pega la invitación ya decodificada, los caracteres no ASCII vienen tal cual
                out.addAll(c.toString().encodeToByteArray().toList())
                i++
            }
        }
        return out.toByteArray().decodeToString()
    }

    private const val HEX = "0123456789ABCDEF"
}
