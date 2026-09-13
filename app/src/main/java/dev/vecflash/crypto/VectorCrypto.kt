package dev.vecflash.crypto

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid

/**
 * Cripto del pairing de Vector, identica a la que usa websetup (libsodium):
 *
 *  - intercambio de claves X25519 via crypto_kx
 *  - las claves de sesion se "mezclan" con el PIN de la cara mediante BLAKE2b con clave
 *  - el trafico va cifrado con XChaCha20-Poly1305-IETF, nonce incremental
 */
class VectorCrypto {

    private val sodium = LazySodiumAndroid(SodiumAndroid())

    companion object {
        const val PUBLICKEY_BYTES = 32
        const val SECRETKEY_BYTES = 32
        const val SESSIONKEY_BYTES = 32
        const val NONCE_BYTES = 24
        const val MAC_BYTES = 16

        /** Equivalente a sodium_increment: suma 1 tratando el buffer como entero little-endian. */
        fun incrementNonce(nonce: ByteArray) {
            var carry = 1
            for (i in nonce.indices) {
                carry += nonce[i].toInt() and 0xFF
                nonce[i] = (carry and 0xFF).toByte()
                carry = carry shr 8
            }
        }
    }

    class KeyPair(val publicKey: ByteArray, val secretKey: ByteArray)

    /** Claves de sesion ya derivadas con el PIN. */
    class SessionKeys(val rx: ByteArray, val tx: ByteArray)

    fun generateKeyPair(): KeyPair {
        val pk = ByteArray(PUBLICKEY_BYTES)
        val sk = ByteArray(SECRETKEY_BYTES)
        check(sodium.cryptoKxKeypair(pk, sk)) { "crypto_kx_keypair fallo" }
        return KeyPair(pk, sk)
    }

    /**
     * Deriva las claves de sesion y las combina con el PIN mostrado en la cara de Vector.
     * rx -> descifrado (robot->app), tx -> cifrado (app->robot).
     */
    fun clientSessionKeys(own: KeyPair, remotePublicKey: ByteArray, pin: String): SessionKeys {
        val rx = ByteArray(SESSIONKEY_BYTES)
        val tx = ByteArray(SESSIONKEY_BYTES)
        check(sodium.cryptoKxClientSessionKeys(rx, tx, own.publicKey, own.secretKey, remotePublicKey)) {
            "crypto_kx_client_session_keys fallo"
        }
        val pinBytes = pin.toByteArray(Charsets.UTF_8)
        return SessionKeys(blake2bKeyed(rx, pinBytes), blake2bKeyed(tx, pinBytes))
    }

    /** crypto_generichash(32, message, key) -> BLAKE2b de 32 bytes con clave. */
    private fun blake2bKeyed(message: ByteArray, key: ByteArray): ByteArray {
        val out = ByteArray(SESSIONKEY_BYTES)
        check(
            sodium.cryptoGenericHash(
                out, out.size,
                message, message.size.toLong(),
                key, key.size
            )
        ) { "crypto_generichash fallo" }
        return out
    }

    fun encrypt(plain: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray {
        val cipher = ByteArray(plain.size + MAC_BYTES)
        val cipherLen = LongArray(1)
        check(
            sodium.cryptoAeadXChaCha20Poly1305IetfEncrypt(
                cipher, cipherLen,
                plain, plain.size.toLong(),
                null, 0L,
                null, nonce, key
            )
        ) { "cifrado fallo" }
        return if (cipherLen[0].toInt() == cipher.size) cipher
               else cipher.copyOf(cipherLen[0].toInt())
    }

    /** Devuelve null si el descifrado falla (PIN incorrecto o sesion desincronizada). */
    fun decrypt(cipher: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray? {
        if (cipher.size < MAC_BYTES) return null
        val plain = ByteArray(cipher.size - MAC_BYTES)
        val plainLen = LongArray(1)
        val ok = try {
            sodium.cryptoAeadXChaCha20Poly1305IetfDecrypt(
                plain, plainLen,
                null,
                cipher, cipher.size.toLong(),
                null, 0L,
                nonce, key
            )
        } catch (e: Throwable) {
            false
        }
        if (!ok) return null
        return if (plainLen[0].toInt() == plain.size) plain
               else plain.copyOf(plainLen[0].toInt())
    }
}
