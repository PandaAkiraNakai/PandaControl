package io.github.pandaakira.apppanda.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Cifra los bearer tokens antes de que Settings los guarde en DataStore
 * (texto plano por defecto — quedaban legibles en
 * /data/data/.../datastore/settings.preferences_pb en un dispositivo
 * rooteado o vía backup). La clave AES-256 vive en el Android Keystore y
 * nunca sale de ahí: ni un root puede exportarla, solo usarla en este mismo
 * dispositivo.
 *
 * Los valores cifrados llevan el prefijo [PREFIX] para poder distinguirlos
 * de un token legacy guardado en texto plano por una versión anterior de la
 * app — [decrypt] devuelve esos tal cual (sin intentar descifrarlos) para no
 * perder el token de nadie al actualizar; se re-cifran solos la próxima vez
 * que ese perfil se guarde.
 */
internal object TokenCrypto {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "panda_token_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_BYTES = 12
    private const val PREFIX = "enc:v1:"

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(KEYSTORE).apply { load(null) }
    }

    private fun getOrCreateKey(): SecretKey {
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
    }

    fun decrypt(stored: String): String {
        if (stored.isEmpty()) return ""
        if (!stored.startsWith(PREFIX)) return stored
        return runCatching {
            val raw = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            val iv = raw.copyOfRange(0, GCM_IV_BYTES)
            val ciphertext = raw.copyOfRange(GCM_IV_BYTES, raw.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrDefault("")
    }
}
