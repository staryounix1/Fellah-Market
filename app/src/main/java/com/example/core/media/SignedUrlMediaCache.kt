package com.example.core.media

import android.content.Context
import com.example.core.auth.SupabaseAuthClient
import com.example.core.network.PostgrestCodec
import com.example.core.network.SupabaseConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class SignedUrlMediaCache(
    private val context: Context,
    private val authClient: SupabaseAuthClient,
    private val baseUrlProvider: () -> String = { SupabaseConfig.url },
    private val anonKeyProvider: () -> String = { SupabaseConfig.anonKey },
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build(),
    private val clockEpochSec: () -> Long = { System.currentTimeMillis() / 1000L }
) {
    private data class CachedSignedUrl(val signedUrl: String, val expiresAtEpochSec: Long)

    private val urlCache = ConcurrentHashMap<String, CachedSignedUrl>()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val mediaDir: File by lazy { File(context.cacheDir, "remote_media").apply { mkdirs() } }

    private fun baseUrl(): String = baseUrlProvider().trim().trimEnd('/')
    private fun anonKey(): String = anonKeyProvider().trim()

    suspend fun resolveLocalOrCachedFile(defaultBucket: String, tokenOrPath: String): File? = withContext(Dispatchers.IO) {
        if (tokenOrPath.isBlank() || tokenOrPath.startsWith("drawable:")) return@withContext null

        val local = File(tokenOrPath)
        if (tokenOrPath.startsWith("/") && local.exists()) {
            return@withContext local
        }

        val (bucket, storagePath) = parseBucketAndPath(defaultBucket, tokenOrPath)
        if (storagePath.isBlank()) return@withContext null

        val targetFile = cachedFileFor(bucket, storagePath)
        if (targetFile.exists() && targetFile.length() > 0L) {
            return@withContext targetFile
        }

        val signedUrl = getOrFetchSignedUrl(bucket, storagePath) ?: return@withContext null
        downloadToFile(signedUrl, targetFile)
    }

    suspend fun getOrFetchSignedUrl(bucket: String, storagePath: String, expiresInSec: Int = 3600): String? =
        withContext(Dispatchers.IO) {
            val base = baseUrl()
            val anon = anonKey()
            if (base.isEmpty() || anon.isEmpty()) return@withContext null

            val cleanPath = storagePath.trimStart('/')
            val cacheKey = "$bucket/$cleanPath"
            val now = clockEpochSec()
            val existing = urlCache[cacheKey]
            if (existing != null && now < existing.expiresAtEpochSec - 120L) {
                return@withContext existing.signedUrl
            }

            val token = authClient.getValidAccessToken().getOrNull() ?: return@withContext null
            val signBody = PostgrestCodec.mapAdapter.toJson(mapOf("expiresIn" to expiresInSec))
            val req = Request.Builder()
                .url("$base/storage/v1/object/sign/$bucket/$cleanPath")
                .header("apikey", anon)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .post(signBody.toRequestBody(jsonMedia))
                .build()

            val fullUrl = runCatching {
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val raw = resp.body?.string().orEmpty()
                    val obj = PostgrestCodec.parseObject(raw) ?: return@use null
                    val signedPart = (obj["signedURL"] as? String) ?: (obj["signedUrl"] as? String) ?: return@use null
                    normalizeSignedUrl(base, signedPart)
                }
            }.getOrNull() ?: return@withContext null

            urlCache[cacheKey] = CachedSignedUrl(fullUrl, now + expiresInSec.toLong())
            fullUrl
        }

    private fun downloadToFile(signedUrl: String, targetFile: File): File? {
        return runCatching {
            val req = Request.Builder().url(signedUrl).get().build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val bytes = resp.body?.bytes() ?: return@use null
                val temp = File(targetFile.parentFile, "${targetFile.name}.tmp")
                temp.writeBytes(bytes)
                if (temp.renameTo(targetFile)) targetFile else {
                    targetFile.writeBytes(bytes)
                    temp.delete()
                    targetFile
                }
            }
        }.getOrNull()
    }

    private fun normalizeSignedUrl(base: String, signedPart: String): String {
        return when {
            signedPart.startsWith("http://") || signedPart.startsWith("https://") -> signedPart
            signedPart.startsWith("/storage/v1/") -> "$base$signedPart"
            signedPart.startsWith("/object/") -> "$base/storage/v1$signedPart"
            else -> "$base/storage/v1/${signedPart.trimStart('/')}"
        }
    }

    private fun parseBucketAndPath(defaultBucket: String, token: String): Pair<String, String> {
        val colonIdx = token.indexOf(':')
        return if (colonIdx > 0 && !token.startsWith("/")) {
            token.substring(0, colonIdx) to token.substring(colonIdx + 1).trimStart('/')
        } else {
            defaultBucket to token.trimStart('/')
        }
    }

    private fun cachedFileFor(bucket: String, path: String): File {
        val ext = path.substringAfterLast('.', "bin").take(5)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$bucket/$path".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(mediaDir, "${bucket}_${digest.take(24)}.$ext")
    }
}
