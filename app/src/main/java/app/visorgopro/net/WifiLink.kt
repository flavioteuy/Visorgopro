package app.visorgopro.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Une la TV al WiFi propio de la cámara sin tocar la red principal (Android 10 o superior). Android pide
 * confirmación al usuario; si la TV no muestra ese aviso, la unión falla y la app pide hacerlo a mano.
 */
class WifiLink(context: Context) {
    private val cm: ConnectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    @RequiresApi(Build.VERSION_CODES.Q)
    suspend fun connect(ssid: String, password: String, timeoutMs: Int = 45_000): Network {
        release()
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(password)
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifier)
            .build()
        return suspendCancellableCoroutine { cont ->
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    // Todo el tráfico de la app pasa por la red de la cámara mientras dure la conexión
                    cm.bindProcessToNetwork(network)
                    if (cont.isActive) cont.resume(network)
                }

                override fun onUnavailable() {
                    if (cont.isActive) {
                        cont.resumeWithException(IOException("La TV no pudo unirse al WiFi de la cámara"))
                    }
                }

                override fun onLost(network: Network) {
                    cm.bindProcessToNetwork(null)
                }
            }
            callback = cb
            cm.requestNetwork(request, cb, timeoutMs)
            cont.invokeOnCancellation { release() }
        }
    }

    fun release() {
        val cb = callback
        callback = null
        if (cb != null) runCatching { cm.unregisterNetworkCallback(cb) }
        runCatching { cm.bindProcessToNetwork(null) }
    }
}
