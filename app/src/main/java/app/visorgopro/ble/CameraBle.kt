package app.visorgopro.ble

import java.io.IOException
import java.util.concurrent.TimeoutException

/** Datos para hablar con la cámara por la red doméstica (HTTP con autenticación básica). */
data class CohnCredentials(val ip: String, val user: String, val pass: String)

private fun idMatch(feature: Int, action: Int): (ByteArray) -> Boolean = { m ->
    m.size >= 2 && (m[0].toInt() and 0xFF) == feature && (m[1].toInt() and 0xFF) == action
}

private fun proto(feature: Int, action: Int, body: ByteArray = ByteArray(0)): ByteArray =
    byteArrayOf(feature.toByte(), action.toByte()) + body

// Identificadores de Open GoPro: función (feature) y acción (action)
private const val F_NET = 0x02
private const val A_CONNECT_NEW = 0x05
private const val A_CONNECT_NEW_RSP = 0x85
private const val A_PROVISIONING = 0x0C
private const val F_COMMAND = 0xF1
private const val F_QUERY = 0xF5
private const val A_CLEAR_CERT = 0x66
private const val A_CLEAR_CERT_RSP = 0xE6
private const val A_CREATE_CERT = 0x67
private const val A_CREATE_CERT_RSP = 0xE7
private const val A_COHN_STATUS = 0x6F
private const val A_COHN_STATUS_RSP = 0xEF

/** Enciende el punto de acceso WiFi de la cámara (comando SET_WIFI 0x17, parámetro 1). */
suspend fun GoProBle.enableWifiAp() {
    val r = request(
        GoProUuids.COMMAND,
        byteArrayOf(0x17, 0x01, 0x01),
        GoProUuids.COMMAND_RESP,
        { it.size >= 2 && (it[0].toInt() and 0xFF) == 0x17 },
    )
    if (r[1].toInt() != 0) throw IOException("La cámara no pudo encender su WiFi (código ${r[1]})")
}

/** Nombre y contraseña del WiFi propio de la cámara. */
suspend fun GoProBle.readWifiCredentials(): Pair<String, String> {
    val ssid = readString(GoProUuids.WIFI_SSID)
    val password = readString(GoProUuids.WIFI_PASSWORD)
    return ssid to password
}

private fun provisioningError(state: Long): String = when (state) {
    7L -> "La cámara no pudo unirse a esa red WiFi. Revisa el nombre y que la red sea WPA2."
    8L -> "La contraseña del WiFi es incorrecta."
    9L -> "La red pide aceptar condiciones de uso y la cámara no puede."
    10L -> "La cámara rechazó la red porque no tiene internet."
    11L -> "La cámara no soporta ese tipo de red WiFi."
    3L, 4L -> "Se canceló la conexión de la cámara a la red."
    else -> "La cámara no pudo conectarse a la red (estado $state)."
}

/** Pide a la cámara que se conecte a una red WiFi (estados de provisionamiento 5 y 6 = éxito). */
suspend fun GoProBle.provisionWifi(ssid: String, password: String) {
    val notifications = openSink(GoProUuids.NET_MGMT_RESP, idMatch(F_NET, A_PROVISIONING))
    try {
        val body = PbWriter().string(1, ssid).string(2, password).bool(10, true).toBytes()
        val response = request(
            GoProUuids.NET_MGMT,
            proto(F_NET, A_CONNECT_NEW, body),
            GoProUuids.NET_MGMT_RESP,
            idMatch(F_NET, A_CONNECT_NEW_RSP),
            15_000,
        )
        val m = PbMessage.parse(response, 2)
        if (m.long(1) != 1L) throw IOException("La cámara rechazó la solicitud de conexión WiFi")
        var state = m.long(2) ?: 0L
        val waitMs = ((m.long(3) ?: 30L) + 15L) * 1000L
        val deadline = System.currentTimeMillis() + waitMs
        while (state != 5L && state != 6L) {
            if (state in 7L..11L || state == 3L || state == 4L) throw IOException(provisioningError(state))
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) throw TimeoutException("La cámara tardó demasiado en conectarse a la red")
            state = PbMessage.parse(notifications.next(left), 2).long(1) ?: state
        }
    } finally {
        closeSink(notifications)
    }
}

private data class CohnStatus(
    val status: Long,
    val state: Long,
    val user: String,
    val pass: String,
    val ip: String,
)

private fun parseCohnStatus(message: ByteArray): CohnStatus {
    val m = PbMessage.parse(message, 2)
    return CohnStatus(
        status = m.long(1) ?: 0L,
        state = m.long(2) ?: 0L,
        user = m.str(3).orEmpty(),
        pass = m.str(4).orEmpty(),
        ip = m.str(5).orEmpty(),
    )
}

/**
 * Deja la cámara lista para usarse desde la red doméstica: borra el certificado anterior, crea uno nuevo y espera a
 * que quede aprovisionada y conectada. La cámara ya debe estar conectada a un WiFi (ver [provisionWifi]).
 */
suspend fun GoProBle.provisionCohn(onProgress: (String) -> Unit): CohnCredentials {
    val sink = openSink(GoProUuids.QUERY_RESP, idMatch(F_QUERY, A_COHN_STATUS_RSP))
    try {
        var last: CohnStatus? = null

        suspend fun waitFor(timeoutMs: Long, what: String, predicate: (CohnStatus) -> Boolean): CohnStatus {
            last?.let { if (predicate(it)) return it }
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) throw TimeoutException("Tiempo agotado esperando: $what")
                val s = parseCohnStatus(sink.next(left))
                last = s
                if (predicate(s)) return s
            }
        }

        // Pedir el estado actual y quedar suscrito a sus cambios
        write(GoProUuids.QUERY, proto(F_QUERY, A_COHN_STATUS, PbWriter().bool(1, true).toBytes()))
        val first = waitFor(10_000, "el estado de COHN") { true }

        onProgress("Borrando el certificado anterior…")
        request(
            GoProUuids.COMMAND,
            proto(F_COMMAND, A_CLEAR_CERT),
            GoProUuids.COMMAND_RESP,
            idMatch(F_COMMAND, A_CLEAR_CERT_RSP),
            10_000,
        )
        if (first.status != 0L) {
            try {
                waitFor(15_000, "borrado del certificado") { it.status == 0L }
            } catch (e: TimeoutException) {
                // puede que ya estuviera sin certificado: se continúa
            }
        }

        onProgress("Creando el certificado de la cámara…")
        val created = request(
            GoProUuids.COMMAND,
            proto(F_COMMAND, A_CREATE_CERT, PbWriter().bool(1, true).toBytes()),
            GoProUuids.COMMAND_RESP,
            idMatch(F_COMMAND, A_CREATE_CERT_RSP),
            20_000,
        )
        if (PbMessage.parse(created, 2).long(1) != 1L) {
            throw IOException("La cámara no pudo crear el certificado de COHN")
        }

        onProgress("Esperando a que la cámara quede conectada a tu red…")
        val ready = waitFor(90_000, "que COHN quede listo") {
            it.status == 1L && it.state == 27L && it.ip.isNotBlank() && it.user.isNotBlank() && it.pass.isNotBlank()
        }
        return CohnCredentials(ready.ip, ready.user, ready.pass)
    } finally {
        closeSink(sink)
    }
}
