package dev.offlinesync.android

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class KeyRecoveryRequiredException(cause: Throwable? = null) :
    IllegalStateException("Encrypted sync storage cannot be unlocked; recovery is required.", cause)

internal class DatabaseKeyStore(private val directory: File, private val alias: String) {
    private val wrappedFile = File(directory, "wrapped-key")

    fun loadOrCreate(databaseExists: Boolean): ByteArray {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val atomic = AtomicFile(wrappedFile)
        if (wrappedFile.exists() || File(directory, "wrapped-key.bak").exists()) {
            try {
                val key = keyStore.getKey(alias, null) as? SecretKey ?: throw KeyRecoveryRequiredException()
                val bytes = atomic.readFully()
                require(bytes.size == 61 && bytes[0] == 1.toByte())
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
                cipher.updateAAD(alias.toByteArray(Charsets.UTF_8))
                return cipher.doFinal(bytes.copyOfRange(13, bytes.size)).also { require(it.size == 32) }
            } catch (failure: Exception) {
                throw KeyRecoveryRequiredException(failure)
            }
        }
        if (databaseExists || keyStore.containsAlias(alias)) throw KeyRecoveryRequiredException()
        directory.mkdirs()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(false)
            .build())
        val key = generator.generateKey()
        val passphrase = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(alias.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(passphrase)
        val bytes = ByteBuffer.allocate(1 + cipher.iv.size + encrypted.size)
            .put(1.toByte()).put(cipher.iv).put(encrypted).array()
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            atomic.finishWrite(output)
        } catch (failure: Exception) {
            atomic.failWrite(output)
            passphrase.fill(0)
            throw failure
        }
        return passphrase
    }
}