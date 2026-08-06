package com.linktomac.crypto

import android.util.Base64
import com.linktomac.net.EncryptedFrame
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * P-256 ECDH + HKDF-SHA256 + AES-256-GCM session crypto, matching docs/PROTOCOL.md and the
 * Mac side's CryptoKit implementation.
 *
 * Public keys are exchanged as raw X9.63 points (0x04 || X || Y, 32 bytes each for P-256) —
 * the same format CryptoKit's `x963Representation` produces — so both sides speak identical
 * bytes on the wire without a shared library.
 */
object SecureChannel {
    private const val FIELD_SIZE = 32

    data class KeyPair(val privateKey: PrivateKey, val publicKeyX963: ByteArray) {
        val publicKeyBase64: String get() = Base64.encodeToString(publicKeyX963, Base64.NO_WRAP)
    }

    fun generateKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = generator.generateKeyPair()
        val x963 = encodeX963(keyPair.public as ECPublicKey)
        return KeyPair(keyPair.private, x963)
    }

    fun deriveSessionKey(privateKey: PrivateKey, peerPublicKeyX963Base64: String, salt: ByteArray): SecretKeySpec {
        val peerKeyBytes = Base64.decode(peerPublicKeyX963Base64, Base64.NO_WRAP)
        val peerPublicKey = decodeX963(peerKeyBytes)
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(peerPublicKey, true)
        val sharedSecret = agreement.generateSecret()
        val keyBytes = Hkdf.derive(sharedSecret, salt, "LinkToMac-v1".toByteArray(Charsets.UTF_8), 32)
        return SecretKeySpec(keyBytes, "AES")
    }

    fun seal(plaintext: ByteArray, key: SecretKeySpec): EncryptedFrame {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        val ciphertext = cipher.doFinal(plaintext)
        return EncryptedFrame(
            nonce = Base64.encodeToString(nonce, Base64.NO_WRAP),
            ciphertext = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        )
    }

    fun open(frame: EncryptedFrame, key: SecretKeySpec): ByteArray {
        val nonce = Base64.decode(frame.nonce, Base64.NO_WRAP)
        val ciphertext = Base64.decode(frame.ciphertext, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
        return cipher.doFinal(ciphertext)
    }

    private fun encodeX963(publicKey: ECPublicKey): ByteArray {
        val x = unsignedBytes(publicKey.w.affineX, FIELD_SIZE)
        val y = unsignedBytes(publicKey.w.affineY, FIELD_SIZE)
        return byteArrayOf(0x04) + x + y
    }

    private fun unsignedBytes(value: BigInteger, length: Int): ByteArray {
        val raw = value.toByteArray()
        val trimmed = if (raw.size > length) raw.copyOfRange(raw.size - length, raw.size) else raw
        return if (trimmed.size < length) ByteArray(length - trimmed.size) + trimmed else trimmed
    }

    private fun decodeX963(bytes: ByteArray): PublicKey {
        require(bytes.size == 1 + 2 * FIELD_SIZE && bytes[0] == 0x04.toByte()) { "expected uncompressed P-256 point" }
        val x = BigInteger(1, bytes.copyOfRange(1, 1 + FIELD_SIZE))
        val y = BigInteger(1, bytes.copyOfRange(1 + FIELD_SIZE, 1 + 2 * FIELD_SIZE))

        val parameters = AlgorithmParameters.getInstance("EC")
        parameters.init(ECGenParameterSpec("secp256r1"))
        val ecParameterSpec = parameters.getParameterSpec(ECParameterSpec::class.java)
        val publicKeySpec = ECPublicKeySpec(ECPoint(x, y), ecParameterSpec)
        return KeyFactory.getInstance("EC").generatePublic(publicKeySpec)
    }
}
