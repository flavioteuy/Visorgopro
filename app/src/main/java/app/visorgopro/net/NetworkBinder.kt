package app.visorgopro.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * En modo AP la TV puede tener Ethernet y WiFi a la vez; se fuerza el tráfico de la app por el WiFi
 * (la red de la cámara). En modo red doméstica se deja la ruta por defecto.
 */
object NetworkBinder {

    fun bindToWifi(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        val wifi = cm.allNetworks.firstOrNull { net ->
            cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        return cm.bindProcessToNetwork(wifi)
    }

    fun unbind(context: Context) {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        cm.bindProcessToNetwork(null)
    }
}
