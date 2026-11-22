package com.example.core.network

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.R
import com.example.core.auth.SessionManager
import com.example.core.data.NotificationEntity
import com.example.core.data.UserRole
import com.example.core.l10n.AppLanguage
import kotlinx.coroutines.*

/**
 * Abstraction for near-real-time notifications & market updates.
 * Currently backed by PollingNotificationSource (every ~35s in foreground).
 * Supabase Realtime WebSockets or FCM push can replace or augment this interface without touching ViewModels.
 */
interface RealtimeNotificationSource {
    fun start(
        scope: CoroutineScope,
        userId: String,
        isAdmin: Boolean,
        isOnline: () -> Boolean,
        languageProvider: () -> AppLanguage
    )

    fun stop()
}

object LocalAndroidNotifier {
    private const val CHANNEL_ID = "fellah_deals_channel"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notifications_title),
                    NotificationManager.IMPORTANCE_HIGH
                )
                nm.createNotificationChannel(channel)
            }
        }
    }

    fun showNotification(context: Context, notif: NotificationEntity, language: AppLanguage) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        ensureChannel(context)
        val title = when (language) {
            AppLanguage.DARIJA -> notif.titleDarija
            AppLanguage.ARABIC -> notif.titleAr
            AppLanguage.FRENCH -> notif.titleFr
        }.ifBlank { context.getString(R.string.app_name) }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_fellah_logo)
            .setContentTitle(title)
            .setContentText(notif.bodyText)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(notif.id.hashCode(), notification)
        }
    }
}

class PollingNotificationSource(
    private val context: Context,
    private val supabaseRepo: SupabaseRepository,
    private val sessionManager: SessionManager,
    private val pollIntervalMs: Long = 35_000L
) : RealtimeNotificationSource {

    private var job: Job? = null
    private var lastNotificationCheckMillis: Long = System.currentTimeMillis() - 60_000L
    private val seenIds = mutableSetOf<String>()

    override fun start(
        scope: CoroutineScope,
        userId: String,
        isAdmin: Boolean,
        isOnline: () -> Boolean,
        languageProvider: () -> AppLanguage
    ) {
        stop()
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(pollIntervalMs)
                if (!isOnline() || !supabaseRepo.isRemoteConfigured() || sessionManager.getSession() == null) {
                    continue
                }
                val newNotifs = supabaseRepo.fetchNotificationsSince(userId, lastNotificationCheckMillis).getOrNull().orEmpty()
                if (newNotifs.isNotEmpty()) {
                    lastNotificationCheckMillis = newNotifs.maxOf { it.createdAt }
                    newNotifs.filter { !it.isRead && seenIds.add(it.id) }.forEach { notif ->
                        LocalAndroidNotifier.showNotification(context, notif, languageProvider())
                    }
                }
                supabaseRepo.refreshAllUserData(userId, isAdmin)
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }
}
