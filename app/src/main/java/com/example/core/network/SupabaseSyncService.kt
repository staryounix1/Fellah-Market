package com.example.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.example.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SupabaseConfig {
    private fun readField(name: String): String = runCatching {
        BuildConfig::class.java.getField(name).get(null) as? String ?: ""
    }.getOrDefault("").trim().trim('"')

    private fun readBoolField(name: String, default: Boolean): Boolean = runCatching {
        BuildConfig::class.java.getField(name).getBoolean(null)
    }.getOrDefault(default)

    val url: String get() = readField("SUPABASE_URL").trimEnd('/')
    val anonKey: String get() = readField("SUPABASE_ANON_KEY")
    val demoMode: Boolean get() = readBoolField("DEMO_MODE", true)
    val isConfigured: Boolean get() = url.isNotEmpty() && anonKey.isNotEmpty()
}

/**
 * Abstraction layer for push/WhatsApp/notification dispatchers.
 * FCM or WhatsApp Business API providers plug in here.
 */
interface NotificationChannelPort {
    suspend fun dispatch(userId: String, title: String, body: String, type: String)
}

class LocalAndFcmNotificationPort : NotificationChannelPort {
    override suspend fun dispatch(userId: String, title: String, body: String, type: String) {
        // In production, sends to Supabase Edge Function -> FCM / WhatsApp.
        // Local Room NotificationEntity is persisted directly by FellahRepository for immediate in-app delivery.
    }
}

class ConnectivityObserver(context: Context) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val _isConnected = MutableStateFlow(checkCurrentConnectivity())
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    init {
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    _isConnected.value = true
                }

                override fun onLost(network: Network) {
                    _isConnected.value = checkCurrentConnectivity()
                }
            })
        }
    }

    private fun checkCurrentConnectivity(): Boolean {
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
