package com.johanwes.cometdownload

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject
import java.io.File

/** The user's settings (the debrid token among them), kept in encrypted preferences. */
class SettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = securePrefs(appContext)

    init {
        importSeedSettings()
        // Subtitles used to need an OpenSubtitles account; don't keep its login around.
        if (LEGACY_KEYS.any(prefs::contains)) {
            prefs.edit().apply { LEGACY_KEYS.forEach(::remove) }.apply()
        }
    }

    fun load() = AppSettings(
        provider = Provider.fromId(prefs.getString(KEY_PROVIDER, null)),
        token = prefs.getString(KEY_TOKEN, "").orEmpty(),
        subtitlesEnabled = prefs.getBoolean(KEY_SUBS_ENABLED, true),
        subtitleLanguages = prefs.getString(KEY_SUB_LANGUAGES, null) ?: DEFAULT_SUBTITLE_LANGUAGES,
    )

    fun save(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_PROVIDER, settings.provider.id)
            .putString(KEY_TOKEN, settings.token)
            .putBoolean(KEY_SUBS_ENABLED, settings.subtitlesEnabled)
            .putString(KEY_SUB_LANGUAGES, settings.subtitleLanguages)
            .apply()
    }

    /**
     * Debug builds only: imports settings pushed over adb (`run-as ... files/seed_settings.json`)
     * into the encrypted store, then deletes the file. Lets credentials be installed without
     * typing them on the device or baking them into the APK.
     */
    private fun importSeedSettings() {
        if (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val file = File(appContext.filesDir, SEED_FILE)
        if (!file.exists()) return
        runCatching {
            val seed = JSONObject(file.readText())
            val editor = prefs.edit()
            mapOf(
                "provider" to KEY_PROVIDER,
                "token" to KEY_TOKEN,
                "subtitle_languages" to KEY_SUB_LANGUAGES,
            ).forEach { (field, key) ->
                seed.optString(field, "").trim().takeIf { it.isNotEmpty() }?.let { editor.putString(key, it) }
            }
            editor.commit()
        }
        file.delete()
    }

    private companion object {
        const val KEY_PROVIDER = "provider"
        const val KEY_TOKEN = "token"
        const val KEY_SUBS_ENABLED = "subtitles_enabled"
        val LEGACY_KEYS = listOf("opensubtitles_api_key", "opensubtitles_username", "opensubtitles_password")
        const val KEY_SUB_LANGUAGES = "subtitle_languages"
        const val SEED_FILE = "seed_settings.json"

        fun securePrefs(context: Context): SharedPreferences = try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "comet_secrets",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (_: Exception) {
            context.getSharedPreferences("comet_secrets_fallback", Context.MODE_PRIVATE)
        }
    }
}
