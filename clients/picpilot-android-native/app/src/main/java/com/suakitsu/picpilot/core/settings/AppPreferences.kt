package com.suakitsu.picpilot.core.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.picPilotPreferences by preferencesDataStore(name = "picpilot_preferences")

@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val store = context.picPilotPreferences

    val theme: Flow<String> = store.data.map { preferences ->
        preferences[THEME]?.takeIf { it in THEMES } ?: "system"
    }

    val language: Flow<String> = store.data.map { preferences ->
        normalizeLanguage(preferences[LANGUAGE])
    }

    suspend fun update(theme: String, language: String) {
        store.edit { preferences ->
            preferences[THEME] = theme.takeIf { it in THEMES } ?: "system"
            preferences[LANGUAGE] = normalizeLanguage(language)
        }
    }

    private fun normalizeLanguage(value: String?): String = when (value) {
        "en", "en-US" -> "en-US"
        "ja", "ja-JP" -> "ja-JP"
        else -> "zh-CN"
    }

    private companion object {
        val THEME = stringPreferencesKey("theme")
        val LANGUAGE = stringPreferencesKey("language")
        val THEMES = setOf("system", "light", "dark")
    }
}
