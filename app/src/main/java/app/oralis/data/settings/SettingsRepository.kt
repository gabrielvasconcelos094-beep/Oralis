package app.oralis.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.oralis.generation.ReadingPreset
import app.oralis.model.ModelVariant
import app.oralis.tts.AccelerationMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Languages Oralis prioritizes. Other languages bundled in Kokoro remain available as "Outros". */
enum class AppLanguage(val tag: String, val label: String, val shortLabel: String) {
    PT_BR("pt-BR", "Português (Brasil)", "Português"),
    EN_GB("en-GB", "English (UK)", "English");

    companion object {
        fun fromTag(tag: String?) = entries.firstOrNull { it.tag == tag } ?: PT_BR
    }
}

data class Settings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val language: AppLanguage = AppLanguage.PT_BR,
    val voicePtBr: String = "pf_dora",
    val voiceEnGb: String = "bf_emma",
    val preset: ReadingPreset = ReadingPreset.READING,
    val bitrateKbps: Int = 96,
    val stripMarkdown: Boolean = true,
    val modelVariant: ModelVariant = ModelVariant.DEFAULT,
    val acceleration: AccelerationMode = AccelerationMode.AUTO,
    val playbackSpeed: Float = 1f,
    val lastPlayedAudioId: String? = null,
) {
    fun voiceFor(language: AppLanguage) = when (language) {
        AppLanguage.PT_BR -> voicePtBr
        AppLanguage.EN_GB -> voiceEnGb
    }
}

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private object K {
        val theme = stringPreferencesKey("theme")
        val language = stringPreferencesKey("language")
        val voicePt = stringPreferencesKey("voice_pt_br")
        val voiceEn = stringPreferencesKey("voice_en_gb")
        val preset = stringPreferencesKey("preset")
        val bitrate = intPreferencesKey("bitrate")
        val strip = booleanPreferencesKey("strip_markdown")
        val variant = stringPreferencesKey("model_variant")
        val accel = stringPreferencesKey("acceleration")
        val speed = floatPreferencesKey("playback_speed")
        val lastPlayed = stringPreferencesKey("last_played")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p -> p.toSettings() }

    private fun Preferences.toSettings(): Settings {
        val d = Settings()
        return Settings(
            theme = this[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.theme,
            language = AppLanguage.fromTag(this[K.language]),
            voicePtBr = this[K.voicePt] ?: d.voicePtBr,
            voiceEnGb = this[K.voiceEn] ?: d.voiceEnGb,
            preset = ReadingPreset.fromId(this[K.preset]),
            bitrateKbps = this[K.bitrate]?.takeIf { it in setOf(64, 96, 128) } ?: d.bitrateKbps,
            stripMarkdown = this[K.strip] ?: d.stripMarkdown,
            modelVariant = ModelVariant.fromId(this[K.variant]),
            acceleration = this[K.accel]?.let { runCatching { AccelerationMode.valueOf(it) }.getOrNull() } ?: d.acceleration,
            playbackSpeed = this[K.speed] ?: d.playbackSpeed,
            lastPlayedAudioId = this[K.lastPlayed],
        )
    }

    suspend fun setTheme(v: ThemeMode) = context.dataStore.edit { it[K.theme] = v.name }
    suspend fun setLanguage(v: AppLanguage) = context.dataStore.edit { it[K.language] = v.tag }
    suspend fun setVoice(language: AppLanguage, voiceId: String) = context.dataStore.edit {
        when (language) {
            AppLanguage.PT_BR -> it[K.voicePt] = voiceId
            AppLanguage.EN_GB -> it[K.voiceEn] = voiceId
        }
    }
    suspend fun setPreset(v: ReadingPreset) = context.dataStore.edit { it[K.preset] = v.id }
    suspend fun setBitrate(v: Int) = context.dataStore.edit { it[K.bitrate] = v }
    suspend fun setStripMarkdown(v: Boolean) = context.dataStore.edit { it[K.strip] = v }
    suspend fun setModelVariant(v: ModelVariant) = context.dataStore.edit { it[K.variant] = v.id }
    suspend fun setAcceleration(v: AccelerationMode) = context.dataStore.edit { it[K.accel] = v.name }
    suspend fun setPlaybackSpeed(v: Float) = context.dataStore.edit { it[K.speed] = v }
    suspend fun setLastPlayed(id: String?) = context.dataStore.edit {
        if (id == null) it.remove(K.lastPlayed) else it[K.lastPlayed] = id
    }
}
