package com.sanabria.motointercom.core

import com.sanabria.motointercom.core.crypto.CryptoProvider
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** CryptoProvider con javax.crypto, solo para las pruebas (la app tiene el suyo). */
class JvmCrypto : CryptoProvider {
    private val rng = SecureRandom()

    override fun randomBytes(n: Int) = ByteArray(n).also { rng.nextBytes(it) }

    override fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    override fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            doFinal(plaintext)
        }

    override fun open(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, off: Int, len: Int): ByteArray? = try {
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            doFinal(ciphertext, off, len)
        }
    } catch (_: AEADBadTagException) {
        null
    }
}
