package com.sanabria.motointercom.core.crypto

import com.sanabria.motointercom.core.protocol.ByteWriter

/**
 * Primitivas criptográficas de la plataforma (javax.crypto en Android, CryptoKit en iOS). El núcleo solo decide qué se
 * cifra y con qué clave; nunca implementa cifrado propio.
 */
interface CryptoProvider {
    fun randomBytes(n: Int): ByteArray

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray

    /** AES-256-GCM con nonce de 12 bytes; devuelve el cifrado con la etiqueta de 16 bytes al final. */
    fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray

    /** Descifra [len] bytes desde [off]; null si la etiqueta no cuadra (clave equivocada o trama manipulada). */
    fun open(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, off: Int, len: Int): ByteArray?
}

/**
 * Claves que salen de la clave de un grupo (32 bytes aleatorios que viajan en el QR). Cada uso tiene la suya,
 * derivada con HMAC-SHA256 y una etiqueta distinta:
 * - [advId]: 4 bytes que van en el anuncio BLE para encontrarse (no revela la clave).
 * - saludo: autentica el Hello de cada enlace.
 * - cifrado: de ella sale una clave nueva para cada enlace, mezclando los nonces aleatorios de los dos extremos.
 */
class GroupKeys(private val crypto: CryptoProvider, groupKey: ByteArray) {
    init {
        require(groupKey.size == KEY_BYTES) { "la clave de grupo son $KEY_BYTES bytes" }
    }

    private val auth = crypto.hmacSha256(groupKey, label("hello"))
    private val enc = crypto.hmacSha256(groupKey, label("enc"))

    val advId: Int = crypto.hmacSha256(groupKey, label("adv")).let {
        val v = ((it[0].toInt() and 0xFF) shl 24) or ((it[1].toInt() and 0xFF) shl 16) or
            ((it[2].toInt() and 0xFF) shl 8) or (it[3].toInt() and 0xFF)
        if (v == 0) 1 else v
    }

    fun helloMac(protocol: Int, nodeId: Int, groupId: Int, nonce: ByteArray): ByteArray {
        val w = ByteWriter(32)
        w.u8(protocol)
        w.u32(nodeId)
        w.u32(groupId)
        w.bytes(nonce)
        return crypto.hmacSha256(auth, w.toByteArray()).copyOf(MAC_BYTES)
    }

    fun linkKey(initiatorNonce: ByteArray, acceptorNonce: ByteArray): ByteArray =
        crypto.hmacSha256(enc, label("link") + initiatorNonce + acceptorNonce)

    companion object {
        const val KEY_BYTES = 32
        const val NONCE_BYTES = 16
        const val MAC_BYTES = 16

        private fun label(s: String) = "motointercom/$s".encodeToByteArray()

        /** Comparación en tiempo constante (no da pistas por lo que tarda). */
        fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
            if (a.size != b.size) return false
            var diff = 0
            for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
            return diff == 0
        }
    }
}

/**
 * Cifrado de un enlace en los dos sentidos. El nonce es sentido + contador: no viaja, porque L2CAP entrega en orden y
 * sin pérdidas y los dos extremos cuentan igual. Lo que se descarta por atasco se descarta antes de cifrar, así que el
 * contador no se desincroniza. Cada enlace tiene clave nueva, así que los contadores empiezan siempre en 0 sin repetir
 * nonce. [seal] solo desde el hilo que escribe y [open] solo desde el que lee.
 */
class LinkCipher(private val crypto: CryptoProvider, private val key: ByteArray, initiator: Boolean) {
    private val txDirection = if (initiator) 1 else 2
    private val rxDirection = if (initiator) 2 else 1
    private var txCounter = 0L
    private var rxCounter = 0L

    fun seal(plain: ByteArray): ByteArray = crypto.seal(key, nonce(txDirection, txCounter++), plain)

    fun open(buf: ByteArray, off: Int, len: Int): ByteArray? = crypto.open(key, nonce(rxDirection, rxCounter++), buf, off, len)

    private fun nonce(direction: Int, counter: Long): ByteArray {
        val n = ByteArray(12)
        n[0] = direction.toByte()
        for (i in 0 until 8) n[11 - i] = (counter ushr (8 * i)).toByte()
        return n
    }

    companion object {
        const val TAG_BYTES = 16
    }
}
