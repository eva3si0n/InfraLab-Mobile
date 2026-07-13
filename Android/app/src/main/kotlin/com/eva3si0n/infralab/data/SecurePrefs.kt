package com.eva3si0n.infralab.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecurePrefs(context: Context) {
    private val prefs = open(context)

    private fun build(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // The Tink keyset lives inside the `secure_prefs` file but is wrapped by an Android Keystore
    // master key. If that pairing breaks — a backup/Smart-Switch restore brings back a keyset whose
    // Keystore key was wiped on uninstall — create() throws AEADBadTagException/KeyStoreException and
    // the app used to crash on launch (AppViewModel → SecurePrefs.<init>). Self-heal: drop the file
    // so the library regenerates a fresh keyset. Values are re-seeded from the bundled seed.json.
    private fun open(context: Context): SharedPreferences =
        try {
            build(context)
        } catch (e: Exception) {
            context.deleteSharedPreferences(FILE)
            build(context)
        }

    fun set(key: String, value: String) = prefs.edit().putString(key, value).apply()
    fun get(key: String): String = prefs.getString(key, "") ?: ""
    fun has(key: String): Boolean = prefs.getString(key, null) != null

    companion object { private const val FILE = "secure_prefs" }
}
