package com.sanabria.motointercom.crypto

import com.sanabria.motointercom.core.crypto.CryptoProvider
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Criptografía del sistema (Conscrypt/BoringSSL; AES-GCM va acelerado por hardware en ARMv8). Un Cipher por hilo:
 * cada enlace cifra en su hilo de escritura y descifra en el de lectura, 50 tramas por segundo.
 */
object AndroidCrypto : CryptoProvider {
    private val rng = SecureRandom()
    private val cipher = ThreadLocal.withInitial { Cipher.getInstance("AES/GCM/NoPadding") }
    private val mac = ThreadLocal.withInitial { Mac.getInstance("HmacSHA256") }

    override fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

    override fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray = mac.get()!!.run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(data)
    }

    override fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray = cipher.get()!!.run {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        doFinal(plaintext)
    }

    override fun open(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, off: Int, len: Int): ByteArray? = try {
        cipher.get()!!.run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            doFinal(ciphertext, off, len)
        }
    } catch (_: AEADBadTagException) {
        null
    }
}
