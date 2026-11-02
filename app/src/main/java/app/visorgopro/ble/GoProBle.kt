package app.visorgopro.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** UUID de Bluetooth LE de las GoPro (Open GoPro). */
object GoProUuids {
    val SERVICE_FEA6: UUID = UUID.fromString("0000fea6-0000-1000-8000-00805f9b34fb")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private fun gp(short: String): UUID = UUID.fromString("b5f9$short-aa8d-11e3-9046-0002a5d5c51b")

    val WIFI_SSID: UUID = gp("0002")
    val WIFI_PASSWORD: UUID = gp("0003")
    val COMMAND: UUID = gp("0072")
    val COMMAND_RESP: UUID = gp("0073")
    val QUERY: UUID = gp("0076")
    val QUERY_RESP: UUID = gp("0077")
    val NET_MGMT: UUID = gp("0091")
    val NET_MGMT_RESP: UUID = gp("0092")
}

data class FoundCamera(val address: String, val name: String, val rssi: Int, val device: BluetoothDevice)

/** Fallo al abrir la conexión GATT; [status] es el código de Android (133 = fallo genérico de conexión). */
class BleConnectException(val status: Int, message: String) : IOException(message)

/** Nombre legible de los códigos de estado GATT más habituales. */
fun gattStatusText(status: Int): String = when (status) {
    8 -> "tiempo de conexión agotado"
    19 -> "la cámara cerró la conexión"
    22 -> "la TV cerró la conexión"
    133 -> "fallo genérico de conexión"
    257 -> "fallo de conexión"
    else -> "error de conexión"
}

/** Busca cámaras GoPro cercanas (anuncian el servicio 0xFEA6 y su nombre empieza por «GoPro»). */
@SuppressLint("MissingPermission")
fun scanGoPros(context: Context): Flow<FoundCamera> = callbackFlow {
    val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    val scanner = adapter?.bluetoothLeScanner
    if (adapter == null || !adapter.isEnabled || scanner == null) {
        close(IllegalStateException("bluetooth_off"))
        return@callbackFlow
    }
    val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: ""
            val hasService = result.scanRecord?.serviceUuids?.any { it.uuid == GoProUuids.SERVICE_FEA6 } == true
            if (hasService || name.startsWith("GoPro", ignoreCase = true)) {
                trySend(FoundCamera(result.device.address, name.ifBlank { "GoPro" }, result.rssi, result.device))
            }
        }

        override fun onScanFailed(errorCode: Int) {
            close(IllegalStateException("scan_failed:$errorCode"))
        }
    }
    val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
    scanner.startScan(null, settings, callback)
    awaitClose { runCatching { scanner.stopScan(callback) } }
}

/**
 * Conexión GATT con una GoPro. Las operaciones se ejecutan de una en una (Android solo admite una pendiente)
 * y los mensajes de la cámara se reensamblan según el esquema de paquetes de Open GoPro.
 */
@SuppressLint("MissingPermission")
class GoProBle(private val context: Context) {

    /** Receptor de mensajes completos que llegan por una característica y cumplen [match]. */
    class Sink internal constructor(internal val uuid: UUID, internal val match: (ByteArray) -> Boolean) {
        internal val channel = Channel<ByteArray>(Channel.UNLIMITED)

        suspend fun next(timeoutMs: Long): ByteArray =
            withTimeoutOrNull(timeoutMs) { channel.receive() }
                ?: throw TimeoutException("La cámara no respondió a tiempo")
    }

    private class GattResult(val status: Int, val value: ByteArray?)

    private companion object {
        const val CONNECT_ATTEMPTS = 3
    }

    private var gatt: BluetoothGatt? = null
    private val opLock = Mutex()

    @Volatile
    private var pending: CompletableDeferred<GattResult>? = null

    /** Callback vigente: los avisos de intentos anteriores (ya cerrados) se ignoran. */
    @Volatile
    private var active: GattCb? = null
    private val sinks = CopyOnWriteArrayList<Sink>()
    private val assemblers = ConcurrentHashMap<UUID, Assembler>()
    private val subscribed = ConcurrentHashMap.newKeySet<UUID>()

    /** Pasa a true cuando se pierde la conexión. */
    val disconnected = MutableStateFlow(false)

    // ------------------------------------------------------------------ Callbacks de Android

    private inner class GattCb(private val attempt: CompletableDeferred<Unit>) : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (active !== this) return
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                attempt.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                disconnected.value = true
                attempt.completeExceptionally(
                    BleConnectException(status, "No se pudo conectar por Bluetooth (código $status: ${gattStatusText(status)})")
                )
                pending?.complete(GattResult(if (status == BluetoothGatt.GATT_SUCCESS) -1 else status, null))
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (active !== this) return
            pending?.complete(GattResult(status, null))
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (active !== this) return
            pending?.complete(GattResult(status, ch.value))
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (active !== this) return
            pending?.complete(GattResult(status, value))
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (active !== this) return
            pending?.complete(GattResult(status, null))
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (active !== this) return
            pending?.complete(GattResult(status, null))
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (active !== this) return
            val v = ch.value ?: return
            onNotify(ch.uuid, v)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            if (active !== this) return
            onNotify(ch.uuid, value)
        }
    }

    // ------------------------------------------------------------------ Operaciones GATT

    private suspend fun gattOp(timeoutMs: Long, start: () -> Boolean): GattResult = opLock.withLock {
        val d = CompletableDeferred<GattResult>()
        pending = d
        try {
            if (!start()) throw IOException("Android rechazó la operación Bluetooth")
            withTimeoutOrNull(timeoutMs) { d.await() }
                ?: throw TimeoutException("La cámara no respondió por Bluetooth")
        } finally {
            pending = null
        }
    }

    private fun findChar(uuid: UUID): BluetoothGattCharacteristic =
        gatt?.services?.flatMap { it.characteristics }?.firstOrNull { it.uuid == uuid }
            ?: throw IOException("La cámara no ofrece la característica Bluetooth $uuid")

    @Suppress("DEPRECATION")
    private fun doWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ch.value = value
            g.writeCharacteristic(ch)
        }

    @Suppress("DEPRECATION")
    private fun doWriteDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
        } else {
            d.value = value
            g.writeDescriptor(d)
        }

    // ------------------------------------------------------------------ Conexión

    /**
     * Abre la conexión GATT y lee los servicios de la cámara. El error 133 de Android es un fallo genérico y a menudo pasajero, así que se reintenta:
     * se cierra del todo el intento fallido, se espera un poco y se vuelve a probar (el último intento usa
     * autoConnect, que en algunos equipos conecta cuando la conexión directa falla). Si el equipo conserva un
     * emparejamiento antiguo con la cámara, se olvida antes del último intento.
     */
    suspend fun connect(device: BluetoothDevice, onProgress: (String) -> Unit = {}) {
        var lastError: Exception? = null
        for (attempt in 1..CONNECT_ATTEMPTS) {
            val last = attempt == CONNECT_ATTEMPTS
            if (attempt > 1) onProgress("Reintentando la conexión (intento $attempt de $CONNECT_ATTEMPTS)…")
            try {
                connectOnce(device, autoConnect = last, timeoutMs = if (last) 25_000 else 12_000)
                delay(600) // deja que la conexión se estabilice antes de pedir los servicios
                onProgress("Leyendo los servicios de la cámara…")
                discoverServices()
                return
            } catch (e: CancellationException) {
                close()
                throw e
            } catch (e: Exception) {
                lastError = e
                close()
                if (last) break
                delay(1_500)
                if (attempt == CONNECT_ATTEMPTS - 1 && forgetBond(device)) {
                    onProgress("Olvidando un emparejamiento antiguo con la cámara…")
                    delay(1_000)
                }
            }
        }
        throw lastError ?: IOException("No se pudo conectar por Bluetooth")
    }

    private suspend fun connectOnce(device: BluetoothDevice, autoConnect: Boolean, timeoutMs: Long) {
        val d = CompletableDeferred<Unit>()
        val cb = GattCb(d)
        active = cb
        disconnected.value = false
        gatt = device.connectGatt(
            context,
            autoConnect,
            cb,
            BluetoothDevice.TRANSPORT_LE,
            BluetoothDevice.PHY_LE_1M_MASK,
        ) ?: throw IOException("Android no pudo iniciar la conexión Bluetooth")
        withTimeoutOrNull(timeoutMs) { d.await() }
            ?: throw TimeoutException("La cámara no respondió por Bluetooth")
    }

    /** Si la TV tiene un emparejamiento previo con la cámara lo borra (si la cámara se restableció, queda obsoleto). */
    private fun forgetBond(device: BluetoothDevice): Boolean {
        if (device.bondState == BluetoothDevice.BOND_NONE) return false
        return runCatching { device.javaClass.getMethod("removeBond").invoke(device) as? Boolean }
            .getOrNull() == true
    }

    private suspend fun discoverServices() {
        val g = gatt ?: throw IOException("Sin conexión Bluetooth")
        val r = gattOp(15_000) { g.discoverServices() }
        if (r.status != BluetoothGatt.GATT_SUCCESS) {
            throw BleConnectException(
                r.status,
                "No se pudieron leer los servicios de la cámara (código ${r.status}: ${gattStatusText(r.status)})",
            )
        }
    }

    /**
     * Leer una característica cifrada (la clave del WiFi) hace que Android inicie el emparejamiento.
     * La cámara debe estar en modo de emparejamiento la primera vez.
     */
    suspend fun pair() {
        var attempt = 0
        while (true) {
            val r = gattOp(40_000) { gatt!!.readCharacteristic(findChar(GoProUuids.WIFI_PASSWORD)) }
            when (r.status) {
                BluetoothGatt.GATT_SUCCESS -> return
                BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION, BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION -> {
                    attempt++
                    if (attempt >= 3) {
                        throw IOException(
                            "No se pudo emparejar con la cámara. Ponla en modo de emparejamiento y, si ya la " +
                                "habías emparejado antes, olvídala en los ajustes Bluetooth de la TV."
                        )
                    }
                    delay(2_000)
                }
                else -> throw IOException("Falló el emparejamiento Bluetooth (código ${r.status})")
            }
        }
    }

    /** Activa las notificaciones de las características de GoPro (respuestas de comandos, consultas y red). */
    suspend fun enableNotifications() {
        val g = gatt ?: throw IOException("Sin conexión Bluetooth")
        for (service in g.services) {
            for (ch in service.characteristics) {
                if (!ch.uuid.toString().startsWith("b5f9")) continue
                val props = ch.properties
                val value: ByteArray? = when {
                    (props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 ->
                        BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ->
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    else -> null
                }
                if (value == null) continue
                val cccd = ch.getDescriptor(GoProUuids.CCCD) ?: continue
                g.setCharacteristicNotification(ch, true)
                val r = gattOp(8_000) { doWriteDescriptor(g, cccd, value) }
                if (r.status == BluetoothGatt.GATT_SUCCESS) subscribed.add(ch.uuid)
            }
        }
        val essential = listOf(GoProUuids.COMMAND_RESP, GoProUuids.QUERY_RESP, GoProUuids.NET_MGMT_RESP)
        if (!subscribed.containsAll(essential)) {
            throw IOException("La cámara no activó todas las notificaciones Bluetooth necesarias")
        }
    }

    fun close() {
        active = null
        sinks.forEach { it.channel.close() }
        sinks.clear()
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
    }

    // ------------------------------------------------------------------ Lectura y escritura

    suspend fun readString(uuid: UUID): String {
        val r = gattOp(10_000) { gatt!!.readCharacteristic(findChar(uuid)) }
        if (r.status != BluetoothGatt.GATT_SUCCESS) {
            throw IOException("No se pudo leer un dato de la cámara (código ${r.status})")
        }
        return (r.value ?: ByteArray(0)).toString(Charsets.UTF_8).trimEnd('\u0000')
    }

    /** Envía un mensaje troceándolo según el esquema de paquetes de Open GoPro (20 bytes por paquete). */
    suspend fun write(uuid: UUID, payload: ByteArray) {
        val g = gatt ?: throw IOException("Sin conexión Bluetooth")
        val ch = findChar(uuid)
        for (packet in frame(payload)) {
            val r = gattOp(6_000) { doWrite(g, ch, packet) }
            if (r.status != BluetoothGatt.GATT_SUCCESS) {
                throw IOException("Falló el envío por Bluetooth (código ${r.status})")
            }
        }
    }

    fun openSink(uuid: UUID, match: (ByteArray) -> Boolean): Sink {
        val s = Sink(uuid, match)
        sinks.add(s)
        return s
    }

    fun closeSink(s: Sink) {
        sinks.remove(s)
        s.channel.close()
    }

    /** Envía [payload] por [writeUuid] y espera la respuesta que cumpla [match] en [respUuid]. */
    suspend fun request(
        writeUuid: UUID,
        payload: ByteArray,
        respUuid: UUID,
        match: (ByteArray) -> Boolean,
        timeoutMs: Long = 8_000,
    ): ByteArray {
        val sink = openSink(respUuid, match)
        try {
            write(writeUuid, payload)
            return sink.next(timeoutMs)
        } finally {
            closeSink(sink)
        }
    }

    // ------------------------------------------------------------------ Paquetes

    private fun onNotify(uuid: UUID, data: ByteArray) {
        val assembler = assemblers.getOrPut(uuid) { Assembler() }
        val message = synchronized(assembler) { assembler.feed(data) } ?: return
        for (s in sinks) {
            if (s.uuid == uuid && s.match(message)) s.channel.trySend(message)
        }
    }

    private fun frame(payload: ByteArray): List<ByteArray> {
        val len = payload.size
        val header = when {
            len < 32 -> byteArrayOf(len.toByte())
            len < 8192 -> byteArrayOf((0x20 or (len shr 8)).toByte(), (len and 0xFF).toByte())
            else -> byteArrayOf(0x40, (len shr 8).toByte(), (len and 0xFF).toByte())
        }
        val full = header + payload
        val packets = ArrayList<ByteArray>()
        var offset = 0
        var counter = 0
        while (offset < full.size) {
            if (offset == 0) {
                val n = minOf(20, full.size)
                packets += full.copyOfRange(0, n)
                offset = n
            } else {
                val n = minOf(19, full.size - offset)
                packets += byteArrayOf((0x80 or (counter and 0x0F)).toByte()) + full.copyOfRange(offset, offset + n)
                offset += n
                counter++
            }
        }
        return packets
    }

    /** Reensambla los mensajes que la cámara reparte en varios paquetes. */
    private class Assembler {
        private var buffer = ByteArrayOutputStream()
        private var remaining = 0

        fun feed(data: ByteArray): ByteArray? {
            if (data.isEmpty()) return null
            val b0 = data[0].toInt() and 0xFF
            val offset: Int
            if ((b0 and 0x80) != 0) {
                offset = 1 // paquete de continuación
            } else {
                buffer = ByteArrayOutputStream()
                when ((b0 and 0x60) shr 5) {
                    0 -> {
                        remaining = b0 and 0x1F
                        offset = 1
                    }
                    1 -> {
                        remaining = ((b0 and 0x1F) shl 8) or (data[1].toInt() and 0xFF)
                        offset = 2
                    }
                    2 -> {
                        remaining = ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
                        offset = 3
                    }
                    else -> return null
                }
            }
            buffer.write(data, offset, data.size - offset)
            remaining -= data.size - offset
            return if (remaining <= 0) buffer.toByteArray() else null
        }
    }
}
