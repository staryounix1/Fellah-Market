package com.example.core.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.core.domain.LockoutState
import com.example.core.domain.LockoutStore
import com.example.core.l10n.AppLanguage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "fellah_prefs")

class PreferencesStore(private val context: Context) : LockoutStore {
    private val keyLanguage = stringPreferencesKey("language_code")
    private val keyHasSelectedLanguage = booleanPreferencesKey("has_selected_language")
    private val keyCurrentUserId = stringPreferencesKey("current_user_id")
    private val keySimulateOffline = booleanPreferencesKey("simulate_offline")
    private val keyFailedLoginAttempts = intPreferencesKey("failed_login_attempts")
    private val keyLockoutUntilMillis = longPreferencesKey("lockout_until_millis")
    private val keyDemoCredentials = stringPreferencesKey("demo_pin_credentials_v1")

    val languageFlow: Flow<AppLanguage> = context.dataStore.data.map { prefs ->
        AppLanguage.fromCode(prefs[keyLanguage])
    }

    val hasSelectedLanguageFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[keyHasSelectedLanguage] ?: false
    }

    val currentUserIdFlow: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[keyCurrentUserId]
    }

    val simulateOfflineFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[keySimulateOffline] ?: false
    }

    suspend fun setLanguage(language: AppLanguage) {
        context.dataStore.edit { prefs ->
            prefs[keyLanguage] = language.code
            prefs[keyHasSelectedLanguage] = true
        }
    }

    suspend fun setCurrentUserId(userId: String?) {
        context.dataStore.edit { prefs ->
            if (userId == null) {
                prefs.remove(keyCurrentUserId)
            } else {
                prefs[keyCurrentUserId] = userId
            }
        }
    }

    suspend fun setSimulateOffline(offline: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[keySimulateOffline] = offline
        }
    }

    override suspend fun read(): LockoutState {
        val prefs = context.dataStore.data.first()
        return LockoutState(
            failedAttempts = prefs[keyFailedLoginAttempts] ?: 0,
            lockoutUntilMillis = prefs[keyLockoutUntilMillis] ?: 0L
        )
    }

    override suspend fun write(state: LockoutState) {
        context.dataStore.edit { prefs ->
            prefs[keyFailedLoginAttempts] = state.failedAttempts
            prefs[keyLockoutUntilMillis] = state.lockoutUntilMillis
        }
    }

    override suspend fun clear() {
        context.dataStore.edit { prefs ->
            prefs.remove(keyFailedLoginAttempts)
            prefs.remove(keyLockoutUntilMillis)
        }
    }

    suspend fun getDemoCredentialsJson(): String? = context.dataStore.data.first()[keyDemoCredentials]

    suspend fun setDemoCredentialsJson(json: String) {
        context.dataStore.edit { prefs ->
            prefs[keyDemoCredentials] = json
        }
    }
}
