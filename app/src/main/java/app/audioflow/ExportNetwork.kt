package app.audioflow

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.work.Constraints
import androidx.work.NetworkType

internal fun usableExportWifi(capabilities: NetworkCapabilities?): Boolean = capabilities != null &&
    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

internal fun exportWifi(context: Context): Network? {
    val manager=context.getSystemService(ConnectivityManager::class.java)
    return manager.activeNetwork?.takeIf { usableExportWifi(manager.getNetworkCapabilities(it)) }
}

internal fun exportWifiAvailable(context: Context, network: Network): Boolean =
    usableExportWifi(context.getSystemService(ConnectivityManager::class.java).getNetworkCapabilities(network))

internal fun exportConstraints(automatic: Boolean, wifiOnly: Boolean): Constraints = Constraints.Builder().apply {
    if(automatic && wifiOnly) {
        // Android 8 falls back to unmetered scheduling; the worker still requires real Wi-Fi.
        setRequiredNetworkRequest(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),NetworkType.UNMETERED)
    } else if(automatic) setRequiredNetworkType(NetworkType.CONNECTED)
}.build()
