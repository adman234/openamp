package io.github.adman234.openamp.data

import android.content.Context
import android.content.SharedPreferences
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

    /** system, light or dark. */
    var theme: String
        get() = sp.getString("theme", "system")!!
        set(v) = sp.edit().putString("theme", v).apply()

    /** Even out loudness between tracks, using the gain Plex worked out. */
    var leveling: Boolean
        get() = sp.getBoolean("leveling", false)
        set(v) = sp.edit().putBoolean("leveling", v).apply()

    /** Tell Plex when a track has been played. Off unless the user turns it on. */
    var reportPlays: Boolean
        get() = sp.getBoolean("reportPlays", false)
        set(v) = sp.edit().putBoolean("reportPlays", v).apply()

    /** Which views besides Home appear in the browse menu, by name. */
    var menuModes: Set<String>
        get() = sp.getStringSet("menuModes", null)?.toSet() ?: setOf("Albums", "Artists", "Playlists")
        set(v) = sp.edit().putStringSet("menuModes", v).apply()

    var grid: Boolean
        get() = sp.getBoolean("grid", false)
        set(v) = sp.edit().putBoolean("grid", v).apply()

    var allowMobile: Boolean
        get() = sp.getBoolean("allowMobile", false)
        set(v) = sp.edit().putBoolean("allowMobile", v).apply()

    /** Address of the DroppedNeedle server that handles music requests. Empty when not used. */
    var dnUrl: String
        get() = sp.getString("dnUrl", "")!!
        set(v) = sp.edit().putString("dnUrl", v.trim().trimEnd('/')).apply()

    @Volatile
    private var cachedDnToken: String? = null

    /** DroppedNeedle's session token, encrypted the same way as the Plex token. */
    var dnToken: String?
        get() = cachedDnToken
            ?: sp.getString("dnToken", null)
                ?.let { runCatching { decrypt(it) }.getOrNull() }
                ?.also { cachedDnToken = it }
        set(value) {
            cachedDnToken = value
            if (value == null) sp.edit().remove("dnToken").apply()
            else sp.edit().putString("dnToken", encrypt(value)).apply()
        }

    fun register(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.registerOnSharedPreferenceChangeListener(listener)

    fun unregister(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.unregisterOnSharedPreferenceChangeListener(listener)

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
