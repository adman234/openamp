package io.github.adman234.openamp.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Settings, plus the Plex token encrypted with a key held in Android Keystore. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("openamp", Context.MODE_PRIVATE)

    val clientId: String = sp.getString("clientId", null)
        ?: UUID.randomUUID().toString().also { sp.edit().putString("clientId", it).apply() }

    @Volatile
    private var cachedToken: String? = null

    var token: String?
        get() = cachedToken
            ?: sp.getString("token", null)
                ?.let { runCatching { decrypt(it) }.getOrNull() }
                ?.also { cachedToken = it }
        set(value) {
            cachedToken = value
            if (value == null) sp.edit().remove("token").apply()
            else sp.edit().putString("token", encrypt(value)).apply()
        }

    var serverName: String
        get() = sp.getString("serverName", "")!!
        set(v) = sp.edit().putString("serverName", v).apply()

    var plexUrl: String
        get() = sp.getString("plexUrl", "")!!
        set(v) = sp.edit().putString("plexUrl", v.trim().trimEnd('/')).apply()

    var fileServiceUrl: String
        get() = sp.getString("fileServiceUrl", "")!!
        set(v) = sp.edit().putString("fileServiceUrl", v.trim().trimEnd('/')).apply()

    var folderUri: String
        get() = sp.getString("folderUri", "")!!
        set(v) = sp.edit().putString("folderUri", v).apply()

    var quality: String
        get() = sp.getString("quality", "medium")!!
        set(v) = sp.edit().putString("quality", v).apply()

    var allowMobile: Boolean
        get() = sp.getBoolean("allowMobile", false)
        set(v) = sp.edit().putBoolean("allowMobile", v).apply()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + sealed, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_LEN))
        return String(cipher.doFinal(bytes, IV_LEN, bytes.size - IV_LEN), Charsets.UTF_8)
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "openamp_token"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LEN = 12
    }
}
