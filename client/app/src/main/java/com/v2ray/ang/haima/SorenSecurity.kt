package com.v2ray.ang.haima

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Protects the existing per-install MMKV key. Signed-image bootstrap does not create a device
 * identity; these names and formats remain stable so updates preserve local profiles/settings.
 */
class SorenKeyStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES,
        Context.MODE_PRIVATE
    )
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    fun hasMmkvCryptKey(): Boolean = preferences.contains(KEY_MMKV_KEY)

    @Synchronized
    fun mmkvCryptKey(): String {
        preferences.getString(KEY_MMKV_KEY, null)?.let { stored ->
            return decryptCache(decode(stored)).toString(Charsets.UTF_8)
        }
        val raw = ByteArray(24).also(SecureRandom()::nextBytes)
        val value = encode(raw)
        raw.fill(0)
        val protectedValue = encode(encryptCache(value.toByteArray(Charsets.UTF_8)))
        check(preferences.edit().putString(KEY_MMKV_KEY, protectedValue).commit())
        return value
    }

    private fun encryptCache(plainText: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, cacheKey())
        return cipher.iv + cipher.doFinal(plainText)
    }

    private fun decryptCache(encrypted: ByteArray): ByteArray {
        require(encrypted.size > GCM_IV_BYTES) { "Invalid cache" }
        val iv = encrypted.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = encrypted.copyOfRange(GCM_IV_BYTES, encrypted.size)
        return Cipher.getInstance(AES_GCM_TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, cacheKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            doFinal(ciphertext)
        }
    }

    private fun cacheKey(): SecretKey {
        (keyStore.getKey(CACHE_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    CACHE_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val CACHE_ALIAS = "soren_cache_aes_v1"
        private const val PREFERENCES = "soren_installation"
        private const val KEY_MMKV_KEY = "mmkv_crypt_key"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128

        private fun encode(bytes: ByteArray): String =
            Base64.encodeToString(bytes, Base64.NO_WRAP)

        private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)
    }
}
