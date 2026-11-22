package com.example.core.auth

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class AuthSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochSec: Long,
    val userId: String,
    val phoneE164: String
)

interface EncryptedStringStore {
    fun putString(key: String, value: String)
    fun getString(key: String): String?
    fun remove(key: String)
}

class InMemoryStringStore : EncryptedStringStore {
    private val map = ConcurrentHashMap<String, String>()
    override fun putString(key: String, value: String) {
        map[key] = value
    }

    override fun getString(key: String): String? = map[key]

    override fun remove(key: String) {
        map.remove(key)
    }
}

/**
 * Hardware-backed Android Keystore AES-256-GCM encrypted storage for auth tokens.
 * Falls back cleanly to in-memory storage only when KeyStore provider is absent (e.g. JVM/Robolectric tests).
 */
class AndroidKeystoreEncryptedStore(context: Context) : EncryptedStringStore {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val fallback = InMemoryStringStore()

    private fun getOrCreateSecretKey(): SecretKey? = runCatching {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existing = ks.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return@runCatching existing

        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        keyGenerator.init(spec)
        keyGenerator.generateKey()
    }.getOrNull()

    override fun putString(key: String, value: String) {
        val secretKey = getOrCreateSecretKey()
        if (secretKey == null) {
            fallback.putString(key, value)
            return
        }
        runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(1 + iv.size + encrypted.size)
            combined[0] = iv.size.toByte()
            System.arraycopy(iv, 0, combined, 1, iv.size)
            System.arraycopy(encrypted, 0, combined, 1 + iv.size, encrypted.size)
            val encoded = Base64.encodeToString(combined, Base64.NO_WRAP)
            prefs.edit().putString(key, encoded).apply()
        }.onFailure {
            fallback.putString(key, value)
        }
    }

    override fun getString(key: String): String? {
        val raw = prefs.getString(key, null) ?: return fallback.getString(key)
        val secretKey = getOrCreateSecretKey() ?: return fallback.getString(key)
        return runCatching {
            val combined = Base64.decode(raw, Base64.NO_WRAP)
            if (combined.size < 2) return@runCatching null
            val ivLen = combined[0].toInt() and 0xFF
            if (combined.size <= 1 + ivLen) return@runCatching null
            val iv = combined.copyOfRange(1, 1 + ivLen)
            val cipherText = combined.copyOfRange(1 + ivLen, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        }.getOrElse {
            prefs.edit().remove(key).apply()
            null
        }
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
        fallback.remove(key)
    }

    companion object {
        private const val PREFS_NAME = "fellah_secure_session_prefs"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "fellah_session_aes_key_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

class SessionManager(
    private val store: EncryptedStringStore,
    private val clockEpochSec: () -> Long = { System.currentTimeMillis() / 1000L }
) {
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter(AuthSession::class.java)

    private val _sessionFlow = MutableStateFlow(loadSessionInternal())
    val sessionFlow: StateFlow<AuthSession?> = _sessionFlow.asStateFlow()

    private fun loadSessionInternal(): AuthSession? {
        val json = store.getString(KEY_SESSION) ?: return null
        return runCatching { adapter.fromJson(json) }.getOrNull()
    }

    fun getSession(): AuthSession? = _sessionFlow.value

    suspend fun saveSession(session: AuthSession) = withContext(Dispatchers.IO) {
        val json = adapter.toJson(session)
        store.putString(KEY_SESSION, json)
        _sessionFlow.value = session
    }

    suspend fun clearSession() = withContext(Dispatchers.IO) {
        store.remove(KEY_SESSION)
        _sessionFlow.value = null
    }

    fun isExpiredOrExpiringSoon(session: AuthSession, leewaySeconds: Long = 60L): Boolean {
        return clockEpochSec() >= (session.expiresAtEpochSec - leewaySeconds)
    }

    companion object {
        private const val KEY_SESSION = "auth_session_v1"
    }
}
