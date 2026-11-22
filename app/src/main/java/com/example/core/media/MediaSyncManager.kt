package com.example.core.media

import android.content.Context
import androidx.work.*
import com.example.core.auth.*
import com.example.core.data.FellahDatabase
import com.example.core.data.MediaStatus
import com.example.core.network.PostgrestCodec
import com.example.core.network.SupabaseConfig
import com.example.core.network.SupabaseRepository
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class MediaUploadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result {
        if (!SupabaseConfig.isConfigured) return Result.success()

        val taskType = inputData.getString(KEY_TASK_TYPE) ?: return Result.failure()
        val targetId = inputData.getString(KEY_TARGET_ID) ?: return Result.failure()
        val userId = inputData.getString(KEY_USER_ID) ?: return Result.failure()

        val db = FellahDatabase.getInstance(applicationContext)
        val dao = db.dao()
        val sessionManager = SessionManager(AndroidKeystoreEncryptedStore(applicationContext))
        val authClient = SupabaseAuthClient(
            baseUrlProvider = { SupabaseConfig.url },
            anonKeyProvider = { SupabaseConfig.anonKey },
            demoModeProvider = { SupabaseConfig.demoMode },
            sessionManager = sessionManager,
            httpClient = httpClient
        )
        val supabaseRepo = SupabaseRepository(
            dao = dao,
            authClient = authClient,
            sessionManager = sessionManager,
            baseUrlProvider = { SupabaseConfig.url },
            anonKeyProvider = { SupabaseConfig.anonKey },
            httpClient = httpClient
        )

        val tokenRes = authClient.getValidAccessToken()
        val token = tokenRes.getOrElse { err ->
            val authErr = err as? AuthException
            return when {
                authErr?.code == AuthErrorCode.NO_NETWORK -> Result.retry()
                else -> {
                    if (taskType == TASK_OFFER_MEDIA) {
                        dao.updateOfferMediaStatus(targetId, MediaStatus.FAILED)
                    }
                    Result.failure()
                }
            }
        }

        return when (taskType) {
            TASK_OFFER_MEDIA -> handleOfferMedia(targetId, userId, token, dao, supabaseRepo)
            TASK_VERIFICATION_DOC -> handleVerificationDoc(targetId, userId, token, dao)
            else -> Result.failure()
        }
    }

    private suspend fun handleOfferMedia(
        offerId: String,
        userId: String,
        token: String,
        dao: com.example.core.data.FellahDao,
        supabaseRepo: SupabaseRepository
    ): Result {
        val rawPhotos = inputData.getStringArray(KEY_PHOTO_PATHS)?.toList().orEmpty()
        val voicePath = inputData.getString(KEY_VOICE_PATH).orEmpty()

        val uploadedPhotoTokens = mutableListOf<String>()
        for ((index, path) in rawPhotos.withIndex()) {
            if (path.startsWith("/") && File(path).exists()) {
                val file = File(path)
                val storagePath = "$userId/${offerId}_$index.jpg"
                val outcome = uploadFile("offer-photos", storagePath, file, "image/jpeg", token)
                when (outcome) {
                    UploadOutcome.SUCCESS -> {
                        supabaseRepo.insertOfferPhotoRecord(offerId, storagePath, index)
                        uploadedPhotoTokens.add(storagePath)
                    }
                    UploadOutcome.RETRYABLE -> {
                        return if (runAttemptCount < 4) Result.retry() else {
                            markOfferMediaFailed(offerId, dao, supabaseRepo)
                            Result.failure()
                        }
                    }
                    UploadOutcome.TERMINAL -> {
                        markOfferMediaFailed(offerId, dao, supabaseRepo)
                        return Result.failure()
                    }
                }
            } else if (path.isNotBlank()) {
                uploadedPhotoTokens.add(path)
            }
        }

        var finalVoicePath = voicePath
        if (voicePath.startsWith("/") && File(voicePath).exists()) {
            val voiceFile = File(voicePath)
            val storagePath = "$userId/$offerId.m4a"
            when (uploadFile("voice-notes", storagePath, voiceFile, "audio/mp4", token)) {
                UploadOutcome.SUCCESS -> finalVoicePath = storagePath
                UploadOutcome.RETRYABLE -> {
                    return if (runAttemptCount < 4) Result.retry() else {
                        markOfferMediaFailed(offerId, dao, supabaseRepo)
                        Result.failure()
                    }
                }
                UploadOutcome.TERMINAL -> {
                    markOfferMediaFailed(offerId, dao, supabaseRepo)
                    return Result.failure()
                }
            }
        }

        val currentOffer = dao.getOfferById(offerId)
        val finalPhotosCsv = if (uploadedPhotoTokens.isNotEmpty()) {
            uploadedPhotoTokens.joinToString(",")
        } else {
            currentOffer?.photoUrisCsv.orEmpty()
        }
        val remoteVoice = finalVoicePath.takeIf { it.isNotBlank() && !it.startsWith("/") }

        supabaseRepo.updateOfferMediaRemote(offerId, MediaStatus.READY, remoteVoice)
        dao.updateOfferMediaComplete(offerId, MediaStatus.READY, finalPhotosCsv, finalVoicePath)
        return Result.success()
    }

    private suspend fun markOfferMediaFailed(
        offerId: String,
        dao: com.example.core.data.FellahDao,
        supabaseRepo: SupabaseRepository
    ) {
        dao.updateOfferMediaStatus(offerId, MediaStatus.FAILED)
        supabaseRepo.updateOfferMediaRemote(offerId, MediaStatus.FAILED, null)
    }

    private suspend fun handleVerificationDoc(
        profileId: String,
        userId: String,
        token: String,
        dao: com.example.core.data.FellahDao
    ): Result {
        val docPath = inputData.getString(KEY_DOC_PATH).orEmpty()
        if (!docPath.startsWith("/")) return Result.success()
        val file = File(docPath)
        if (!file.exists()) return Result.failure()

        val storagePath = "$userId/id_doc.jpg"
        return when (uploadFile("verification-docs", storagePath, file, "image/jpeg", token)) {
            UploadOutcome.SUCCESS -> {
                val patchBody = PostgrestCodec.mapAdapter.toJson(
                    mapOf(
                        "verification_doc_path" to storagePath,
                        "updated_at" to PostgrestCodec.formatTimestampIso(System.currentTimeMillis())
                    )
                )
                val req = Request.Builder()
                    .url("${SupabaseConfig.url}/rest/v1/profiles?id=eq.$profileId")
                    .header("apikey", SupabaseConfig.anonKey)
                    .header("Authorization", "Bearer $token")
                    .header("Content-Type", "application/json")
                    .patch(patchBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                runCatching { httpClient.newCall(req).execute().close() }
                dao.updateProfileVerificationDoc(profileId, storagePath)
                Result.success()
            }
            UploadOutcome.RETRYABLE -> if (runAttemptCount < 4) Result.retry() else Result.failure()
            UploadOutcome.TERMINAL -> Result.failure()
        }
    }

    private enum class UploadOutcome { SUCCESS, RETRYABLE, TERMINAL }

    private fun uploadFile(
        bucket: String,
        storagePath: String,
        file: File,
        mimeType: String,
        token: String
    ): UploadOutcome {
        val url = "${SupabaseConfig.url}/storage/v1/object/$bucket/${storagePath.trimStart('/')}"
        val req = Request.Builder()
            .url(url)
            .header("apikey", SupabaseConfig.anonKey)
            .header("Authorization", "Bearer $token")
            .header("x-upsert", "true")
            .header("Content-Type", mimeType)
            .post(file.asRequestBody(mimeType.toMediaType()))
            .build()

        return try {
            httpClient.newCall(req).execute().use { resp ->
                when {
                    resp.isSuccessful -> UploadOutcome.SUCCESS
                    resp.code >= 500 || resp.code == 429 || resp.code == 408 -> UploadOutcome.RETRYABLE
                    else -> UploadOutcome.TERMINAL
                }
            }
        } catch (_: IOException) {
            UploadOutcome.RETRYABLE
        } catch (_: Throwable) {
            UploadOutcome.TERMINAL
        }
    }

    companion object {
        const val KEY_TASK_TYPE = "task_type"
        const val KEY_TARGET_ID = "target_id"
        const val KEY_USER_ID = "user_id"
        const val KEY_PHOTO_PATHS = "photo_paths"
        const val KEY_VOICE_PATH = "voice_path"
        const val KEY_DOC_PATH = "doc_path"
        const val TASK_OFFER_MEDIA = "offer_media"
        const val TASK_VERIFICATION_DOC = "verification_doc"
    }
}

class MediaSyncManager(private val context: Context) {
    private val workManager by lazy { WorkManager.getInstance(context.applicationContext) }

    fun enqueueOfferMediaUpload(
        offerId: String,
        userId: String,
        photoPaths: List<String>,
        voicePath: String
    ) {
        if (!SupabaseConfig.isConfigured) return
        val hasLocalPhotos = photoPaths.any { it.startsWith("/") }
        val hasLocalVoice = voicePath.startsWith("/")
        if (!hasLocalPhotos && !hasLocalVoice) return

        val data = Data.Builder()
            .putString(MediaUploadWorker.KEY_TASK_TYPE, MediaUploadWorker.TASK_OFFER_MEDIA)
            .putString(MediaUploadWorker.KEY_TARGET_ID, offerId)
            .putString(MediaUploadWorker.KEY_USER_ID, userId)
            .putStringArray(MediaUploadWorker.KEY_PHOTO_PATHS, photoPaths.toTypedArray())
            .putString(MediaUploadWorker.KEY_VOICE_PATH, voicePath)
            .build()

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val req = OneTimeWorkRequestBuilder<MediaUploadWorker>()
            .setInputData(data)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .build()

        workManager.enqueueUniqueWork(
            "media_offer_$offerId",
            ExistingWorkPolicy.REPLACE,
            req
        )
    }

    fun enqueueVerificationDocUpload(
        profileId: String,
        userId: String,
        docPath: String
    ) {
        if (!SupabaseConfig.isConfigured || !docPath.startsWith("/")) return
        val data = Data.Builder()
            .putString(MediaUploadWorker.KEY_TASK_TYPE, MediaUploadWorker.TASK_VERIFICATION_DOC)
            .putString(MediaUploadWorker.KEY_TARGET_ID, profileId)
            .putString(MediaUploadWorker.KEY_USER_ID, userId)
            .putString(MediaUploadWorker.KEY_DOC_PATH, docPath)
            .build()

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val req = OneTimeWorkRequestBuilder<MediaUploadWorker>()
            .setInputData(data)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .build()

        workManager.enqueueUniqueWork(
            "media_doc_$profileId",
            ExistingWorkPolicy.REPLACE,
            req
        )
    }
}
