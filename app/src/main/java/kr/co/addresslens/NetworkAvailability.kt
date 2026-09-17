package kr.co.addresslens

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper

/** Network state only gates remote requests, never camera OCR or dictionary lookup. */
class NetworkAvailability(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(ConnectivityManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var listener: ((Boolean) -> Unit)? = null
    private var registered = false
    private var previous: Boolean? = null
    private val refresh = Runnable {
        if (listener != null) publish(isOnline(appContext))
    }
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            // Read the app's default network off the connectivity callback thread.
            handler.post(refresh)
        }
        override fun onLost(network: Network) { handler.post(refresh) }
        override fun onUnavailable() { handler.post(refresh) }
    }

    fun start(onChanged: (Boolean) -> Unit) {
        listener = onChanged
        previous = null
        publish(isOnline(appContext))
        if (registered || manager == null) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                manager.registerDefaultNetworkCallback(callback)
            } else {
                manager.registerNetworkCallback(
                    NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                    callback
                )
            }
            registered = true
        } catch (_: RuntimeException) {
            // Requests also check connectivity; observer failure must not stop local recognition.
        }
    }

    fun stop() {
        listener = null
        if (registered) {
            try { manager?.unregisterNetworkCallback(callback) } catch (_: RuntimeException) { }
            registered = false
        }
        handler.removeCallbacks(refresh)
    }

    private fun publish(online: Boolean) {
        if (previous == online) return
        previous = online
        listener?.invoke(online)
    }

    companion object {
        fun isOnline(context: Context): Boolean = try {
            if (ApiSettingsStore.offlineMode(context)) {
                false
            } else {
                val manager = context.getSystemService(ConnectivityManager::class.java)
                val capabilities = manager?.getNetworkCapabilities(manager.activeNetwork)
                capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
        } catch (_: RuntimeException) {
            false
        }
    }
}
