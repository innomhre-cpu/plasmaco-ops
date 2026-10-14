@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package android.net

abstract class Uri : android.os.Parcelable {
    abstract val lastPathSegment: String?
}

class Network

class NetworkCapabilities {
    fun hasCapability(capability: Int): Boolean = TODO()
    companion object {
        const val NET_CAPABILITY_INTERNET = 12
        const val NET_CAPABILITY_VALIDATED = 16
    }
}

class ConnectivityManager {
    val activeNetwork: Network? get() = TODO()
    fun getNetworkCapabilities(network: Network?): NetworkCapabilities? = TODO()
    fun registerDefaultNetworkCallback(callback: NetworkCallback) {}
    fun unregisterNetworkCallback(callback: NetworkCallback) {}
    open class NetworkCallback {
        open fun onAvailable(network: Network) {}
        open fun onLost(network: Network) {}
        open fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {}
    }
}
