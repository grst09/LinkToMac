package com.linktomac.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Minimal RFC 5869 HKDF-SHA256 — the JCA has no built-in HKDF. */
object Hkdf {
    private const val HASH_LEN = 32
    private const val ALGORITHM = "HmacSHA256"

    fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = extract(salt, ikm)
        return expand(prk, info, length)
    }

    private fun extract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance(ALGORITHM)
        val saltKey = if (salt.isEmpty()) ByteArray(HASH_LEN) else salt
        mac.init(SecretKeySpec(saltKey, ALGORITHM))
        return mac.doFinal(ikm)
    }

    private fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(prk, ALGORITHM))
        val output = ByteArray(length)
        var previous = ByteArray(0)
        var generated = 0
        var counter = 1
        while (generated < length) {
            mac.reset()
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val toCopy = minOf(previous.size, length - generated)
            previous.copyInto(output, generated, 0, toCopy)
            generated += toCopy
            counter++
        }
        return output
    }
}
