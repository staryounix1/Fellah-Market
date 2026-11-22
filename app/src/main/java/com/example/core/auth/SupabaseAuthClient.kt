package com.example.core.auth

import com.example.core.domain.InMemoryLockoutStore
import com.example.core.domain.LockoutStore
import com.example.core.domain.PhoneValidator
import com.example.core.domain.PinLockoutManager
import com.example.core.domain.PinValidationError
import com.example.core.domain.PinValidator
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class AuthErrorCode {
    INVALID_PHONE,
    WEAK_PIN,
    PIN_MISMATCH,
    INVALID_CREDENTIALS,
    PHONE_ALREADY_REGISTERED,
    WRONG_CURRENT_PIN,
    ACCOUNT_LOCKED,
    RATE_LIMITED,
    NO_NETWORK,
    SESSION_EXPIRED,
    NOT_CONFIGURED,
    FORBIDDEN,
    SERVER_ERROR
}

class AuthException(
    val code: AuthErrorCode,
    message: String = code.name,
    val lockoutRemainingSeconds: Int = 0,
    val pinError: PinValidationError? = null
) : Exception(message)

class SupabaseAuthClient(
    private val baseUrlProvider: () -> String,
    private val anonKeyProvider: () -> String,
    private val demoModeProvider: () -> Boolean,
    private val sessionManager: SessionManager,
    lockoutStore: LockoutStore = InMemoryLockoutStore(),
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
    private val clockEpochSec: () -> Long = { System.currentTimeMillis() / 1000L },
    clockMillis: () -> Long = { System.currentTimeMillis() }
) {
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val mapType = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    private val mapAdapter = moshi.adapter<Map<String, Any?>>(mapType)
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val refreshMutex = Mutex()
    val lockoutManager = PinLockoutManager(lockoutStore, clockMillis)

    private fun baseUrl(): String = baseUrlProvider().trim().trimEnd('/')
    private fun anonKey(): String = anonKeyProvider().trim()
    private fun isRemoteConfigured(): Boolean = baseUrl().isNotEmpty() && anonKey().isNotEmpty()

    suspend fun signUpWithPhoneAndPin(
        rawPhone: String,
        pin: String,
        confirmPin: String,
        metadata: Map<String, Any?> = emptyMap()
    ): Result<AuthSession?> = withContext(Dispatchers.IO) {
        if (!PhoneValidator.isValidMoroccanPhone(rawPhone)) {
            return@withContext Result.failure(AuthException(AuthErrorCode.INVALID_PHONE))
        }
        val pinErr = PinValidator.validateWithConfirmation(pin, confirmPin)
        if (pinErr != null) {
            return@withContext Result.failure(pinValidationToAuthException(pinErr))
        }

        val e164 = PhoneValidator.toE164Moroccan(rawPhone)
        val syntheticEmail = PhoneValidator.toSyntheticEmail(rawPhone)

        if (!isRemoteConfigured()) {
            return@withContext if (demoModeProvider()) {
                Result.success(null)
            } else {
                Result.failure(AuthException(AuthErrorCode.NOT_CONFIGURED))
            }
        }

        val dataMap = metadata.toMutableMap().apply { put("phone", e164) }
        val payload = mapAdapter.toJson(
            mapOf(
                "email" to syntheticEmail,
                "password" to pin,
                "data" to dataMap
            )
        )
        val request = Request.Builder()
            .url("${baseUrl()}/auth/v1/signup")
            .header("apikey", anonKey())
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody(jsonMedia))
            .build()

        executeAuthCall(request, isSignup = true) { _, body ->
            val session = parseSessionResponse(body, e164)
                ?: throw AuthException(AuthErrorCode.SERVER_ERROR, "Missing session (disable Confirm email in Supabase Auth)")
            sessionManager.saveSession(session)
            lockoutManager.recordSuccess()
            session
        }
    }

    suspend fun loginWithPhoneAndPin(rawPhone: String, pin: String): Result<AuthSession?> = withContext(Dispatchers.IO) {
        val remaining = lockoutManager.remainingLockoutSeconds()
        if (remaining > 0) {
            return@withContext Result.failure(
                AuthException(AuthErrorCode.ACCOUNT_LOCKED, lockoutRemainingSeconds = remaining)
            )
        }

        if (!PhoneValidator.isValidMoroccanPhone(rawPhone)) {
            return@withContext Result.failure(AuthException(AuthErrorCode.INVALID_PHONE))
        }
        if (pin.length < PinValidator.MIN_LENGTH || pin.any { !it.isDigit() }) {
            val state = lockoutManager.recordFailure()
            return@withContext Result.failure(
                if (state.lockoutUntilMillis > 0L) {
                    AuthException(AuthErrorCode.ACCOUNT_LOCKED, lockoutRemainingSeconds = lockoutManager.remainingLockoutSeconds())
                } else {
                    AuthException(AuthErrorCode.INVALID_CREDENTIALS)
                }
            )
        }

        val e164 = PhoneValidator.toE164Moroccan(rawPhone)
        val syntheticEmail = PhoneValidator.toSyntheticEmail(rawPhone)

        if (!isRemoteConfigured()) {
            return@withContext if (demoModeProvider()) {
                Result.success(null)
            } else {
                Result.failure(AuthException(AuthErrorCode.NOT_CONFIGURED))
            }
        }

        val payload = mapAdapter.toJson(
            mapOf(
                "email" to syntheticEmail,
                "password" to pin
            )
        )
        val request = Request.Builder()
            .url("${baseUrl()}/auth/v1/token?grant_type=password")
            .header("apikey", anonKey())
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody(jsonMedia))
            .build()

        val result = executeAuthCall(request, isSignup = false) { _, body ->
            val session = parseSessionResponse(body, e164)
                ?: throw AuthException(AuthErrorCode.SERVER_ERROR, "Missing session in login response")
            sessionManager.saveSession(session)
            lockoutManager.recordSuccess()
            session
        }

        if (result.isFailure) {
            val err = result.exceptionOrNull() as? AuthException
            if (err?.code == AuthErrorCode.INVALID_CREDENTIALS) {
                val state = lockoutManager.recordFailure()
                if (state.lockoutUntilMillis > 0L) {
                    return@withContext Result.failure(
                        AuthException(AuthErrorCode.ACCOUNT_LOCKED, lockoutRemainingSeconds = lockoutManager.remainingLockoutSeconds())
                    )
                }
            }
        }
        result
    }

    suspend fun changePin(
        currentPin: String,
        newPin: String,
        confirmNewPin: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (currentPin.length < PinValidator.MIN_LENGTH || currentPin.any { !it.isDigit() }) {
            return@withContext Result.failure(AuthException(AuthErrorCode.WRONG_CURRENT_PIN))
        }
        val pinErr = PinValidator.validateWithConfirmation(newPin, confirmNewPin)
        if (pinErr != null) {
            return@withContext Result.failure(pinValidationToAuthException(pinErr))
        }

        if (!isRemoteConfigured()) {
            return@withContext if (demoModeProvider()) {
                Result.success(Unit)
            } else {
                Result.failure(AuthException(AuthErrorCode.NOT_CONFIGURED))
            }
        }

        val session = sessionManager.getSession()
            ?: return@withContext Result.failure(AuthException(AuthErrorCode.SESSION_EXPIRED))

        // Verify current PIN via password grant before updating
        val verifyPayload = mapAdapter.toJson(
            mapOf(
                "email" to PhoneValidator.toSyntheticEmail(session.phoneE164),
                "password" to currentPin
            )
        )
        val verifyReq = Request.Builder()
            .url("${baseUrl()}/auth/v1/token?grant_type=password")
            .header("apikey", anonKey())
            .header("Content-Type", "application/json")
            .post(verifyPayload.toRequestBody(jsonMedia))
            .build()

        val reauthRes = executeAuthCall(verifyReq, isSignup = false) { _, body ->
            val refreshed = parseSessionResponse(body, session.phoneE164)
                ?: throw AuthException(AuthErrorCode.SESSION_EXPIRED)
            sessionManager.saveSession(refreshed)
            refreshed.accessToken
        }
        if (reauthRes.isFailure) {
            val err = reauthRes.exceptionOrNull() as? AuthException
            return@withContext Result.failure(
                if (err?.code == AuthErrorCode.INVALID_CREDENTIALS) AuthException(AuthErrorCode.WRONG_CURRENT_PIN)
                else (err ?: AuthException(AuthErrorCode.SERVER_ERROR))
            )
        }

        val activeToken = reauthRes.getOrThrow()
        val updatePayload = mapAdapter.toJson(mapOf("password" to newPin))
        val updateReq = Request.Builder()
            .url("${baseUrl()}/auth/v1/user")
            .header("apikey", anonKey())
            .header("Authorization", "Bearer $activeToken")
            .header("Content-Type", "application/json")
            .put(updatePayload.toRequestBody(jsonMedia))
            .build()

        executeAuthCall(updateReq, isSignup = false) { _, _ -> Unit }
    }

    suspend fun adminResetUserPin(targetUserId: String, temporaryPin: String): Result<Unit> = withContext(Dispatchers.IO) {
        val pinErr = PinValidator.validate(temporaryPin)
        if (pinErr != null) {
            return@withContext Result.failure(pinValidationToAuthException(pinErr))
        }
        if (!isRemoteConfigured()) {
            return@withContext if (demoModeProvider()) {
                Result.success(Unit)
            } else {
                Result.failure(AuthException(AuthErrorCode.NOT_CONFIGURED))
            }
        }
        val token = getValidAccessToken().getOrElse { err ->
            return@withContext Result.failure(err)
        }
        val payload = mapAdapter.toJson(
            mapOf(
                "target_user_id" to targetUserId,
                "temporary_pin" to temporaryPin
            )
        )
        val req = Request.Builder()
            .url("${baseUrl()}/functions/v1/admin-reset-pin")
            .header("apikey", anonKey())
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody(jsonMedia))
            .build()

        executeAuthCall(req, isSignup = false) { _, _ -> Unit }
    }

    suspend fun getValidAccessToken(): Result<String> = withContext(Dispatchers.IO) {
        val current = sessionManager.getSession()
            ?: return@withContext Result.failure(AuthException(AuthErrorCode.SESSION_EXPIRED))
        if (!sessionManager.isExpiredOrExpiringSoon(current)) {
            return@withContext Result.success(current.accessToken)
        }
        refreshSession().map { it.accessToken }
    }

    suspend fun refreshSession(): Result<AuthSession> = refreshMutex.withLock {
        withContext(Dispatchers.IO) {
            val current = sessionManager.getSession()
                ?: return@withContext Result.failure(AuthException(AuthErrorCode.SESSION_EXPIRED))
            if (!sessionManager.isExpiredOrExpiringSoon(current)) {
                return@withContext Result.success(current)
            }
            if (!isRemoteConfigured()) {
                return@withContext Result.failure(AuthException(AuthErrorCode.NOT_CONFIGURED))
            }

            val payload = mapAdapter.toJson(mapOf("refresh_token" to current.refreshToken))
            val request = Request.Builder()
                .url("${baseUrl()}/auth/v1/token?grant_type=refresh_token")
                .header("apikey", anonKey())
                .header("Content-Type", "application/json")
                .post(payload.toRequestBody(jsonMedia))
                .build()

            val res = executeAuthCall(request, isSignup = false) { _, body ->
                val refreshed = parseSessionResponse(body, current.phoneE164)
                    ?: throw AuthException(AuthErrorCode.SESSION_EXPIRED)
                sessionManager.saveSession(refreshed)
                refreshed
            }
            if (res.isFailure) {
                val err = res.exceptionOrNull()
                if (err is AuthException && err.code != AuthErrorCode.NO_NETWORK) {
                    sessionManager.clearSession()
                    return@withContext Result.failure(AuthException(AuthErrorCode.SESSION_EXPIRED))
                }
            }
            res
        }
    }

    suspend fun logout(): Unit = withContext(Dispatchers.IO) {
        val current = sessionManager.getSession()
        if (current != null && isRemoteConfigured()) {
            runCatching {
                val request = Request.Builder()
                    .url("${baseUrl()}/auth/v1/logout")
                    .header("apikey", anonKey())
                    .header("Authorization", "Bearer ${current.accessToken}")
                    .post("{}".toRequestBody(jsonMedia))
                    .build()
                httpClient.newCall(request).execute().close()
            }
        }
        sessionManager.clearSession()
    }

    private fun pinValidationToAuthException(err: PinValidationError): AuthException {
        return when (err) {
            PinValidationError.MISMATCH -> AuthException(AuthErrorCode.PIN_MISMATCH, pinError = err)
            else -> AuthException(AuthErrorCode.WEAK_PIN, pinError = err)
        }
    }

    private suspend fun <T> executeAuthCall(
        request: Request,
        isSignup: Boolean,
        onSuccess: suspend (Int, String) -> T
    ): Result<T> {
        return try {
            httpClient.newCall(request).execute().use { resp ->
                val rawBody = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return Result.failure(mapHttpError(resp.code, rawBody, isSignup))
                }
                Result.success(onSuccess(resp.code, rawBody))
            }
        } catch (_: IOException) {
            Result.failure(AuthException(AuthErrorCode.NO_NETWORK, "Network error"))
        } catch (auth: AuthException) {
            Result.failure(auth)
        } catch (t: Throwable) {
            Result.failure(AuthException(AuthErrorCode.SERVER_ERROR, t.message ?: "Auth error"))
        }
    }

    private fun parseSessionResponse(rawBody: String, fallbackPhone: String): AuthSession? {
        val map = runCatching { mapAdapter.fromJson(rawBody) }.getOrNull() ?: return null
        val accessToken = map["access_token"] as? String ?: return null
        val refreshToken = map["refresh_token"] as? String ?: ""
        val expiresAt = (map["expires_at"] as? Number)?.toLong()
            ?: ((map["expires_in"] as? Number)?.toLong()?.let { clockEpochSec() + it })
            ?: (clockEpochSec() + 3600L)
        @Suppress("UNCHECKED_CAST")
        val userMap = map["user"] as? Map<String, Any?>
        val userId = userMap?.get("id") as? String ?: return null
        @Suppress("UNCHECKED_CAST")
        val userMeta = userMap["user_metadata"] as? Map<String, Any?>
        val phone = (userMap["phone"] as? String)?.takeIf { it.isNotBlank() }
            ?: (userMeta?.get("phone") as? String)?.takeIf { it.isNotBlank() }
            ?: fallbackPhone
        return AuthSession(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtEpochSec = expiresAt,
            userId = userId,
            phoneE164 = phone
        )
    }

    private fun mapHttpError(statusCode: Int, rawBody: String, isSignup: Boolean): AuthException {
        val parsed = runCatching { mapAdapter.fromJson(rawBody) }.getOrNull().orEmpty()
        val errorCode = (parsed["error_code"] as? String ?: parsed["error"] as? String ?: "").lowercase()
        val msg = (parsed["msg"] as? String ?: parsed["message"] as? String ?: parsed["error_description"] as? String ?: "").lowercase()

        return when {
            statusCode == 429 || "rate_limit" in errorCode || "rate limit" in msg || "too many" in msg ->
                AuthException(AuthErrorCode.RATE_LIMITED)
            isSignup && (
                statusCode == 409 ||
                    "user_already_exists" in errorCode ||
                    "already" in msg ||
                    "duplicate" in msg ||
                    "database error saving new user" in msg
                ) ->
                AuthException(AuthErrorCode.PHONE_ALREADY_REGISTERED)
            isSignup && ("weak_password" in errorCode || "password" in msg && "short" in msg) ->
                AuthException(AuthErrorCode.WEAK_PIN, pinError = PinValidationError.TOO_SHORT)
            statusCode == 403 ->
                AuthException(AuthErrorCode.FORBIDDEN)
            statusCode in listOf(400, 401) && (
                "invalid_grant" in errorCode ||
                    "invalid_credentials" in errorCode ||
                    "invalid login" in msg ||
                    "invalid email or password" in msg ||
                    "credentials" in msg
                ) ->
                AuthException(AuthErrorCode.INVALID_CREDENTIALS)
            statusCode == 401 ->
                AuthException(AuthErrorCode.SESSION_EXPIRED)
            else ->
                AuthException(AuthErrorCode.SERVER_ERROR)
        }
    }
}
