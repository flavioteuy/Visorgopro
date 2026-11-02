package app.visorgopro.ble

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import app.visorgopro.AppVm
import app.visorgopro.net.Config
import app.visorgopro.net.ConnMode
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class BtStage { List, Working, Ready, HomeForm, ManualWifi }

fun blePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

fun hasBlePermissions(context: Context): Boolean =
    blePermissions().all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

/**
 * Lógica de la pantalla Bluetooth: buscar y emparejar la cámara y, desde ahí, o bien encender su WiFi y unir la TV,
 * o bien configurar la cámara en la red doméstica (COHN).
 */
class BluetoothFlow(
    private val app: Application,
    private val scope: CoroutineScope,
    private val vm: AppVm,
    private val prefs: SharedPreferences,
) {
    var stage by mutableStateOf(BtStage.List)
        private set
    var scanning by mutableStateOf(false)
        private set
    var cameras by mutableStateOf<List<FoundCamera>>(emptyList())
        private set
    var log by mutableStateOf<List<String>>(emptyList())
        private set
    var error by mutableStateOf<String?>(null)
    var cameraName by mutableStateOf("")
        private set
    var apSsid by mutableStateOf("")
        private set
    var apPass by mutableStateOf("")
        private set
    var homeSsid by mutableStateOf(prefs.getString("home_ssid", "") ?: "")
    var homePass by mutableStateOf("")

    private var ble: GoProBle? = null
    private var scanJob: Job? = null
    private var workJob: Job? = null
    private var watchJob: Job? = null

    private fun step(text: String) {
        log = (log + text).takeLast(8)
    }

    /** Vuelve al estado inicial al abrir la pantalla. */
    fun reset() {
        close()
        stage = BtStage.List
        cameras = emptyList()
        error = null
        log = emptyList()
    }

    /** Cierra todo lo relacionado con Bluetooth. */
    fun close() {
        stopScan()
        workJob?.cancel()
        workJob = null
        closeBle()
    }

    private fun closeBle() {
        watchJob?.cancel()
        watchJob = null
        val b = ble
        ble = null
        b?.close()
    }

    // ------------------------------------------------------------------ Búsqueda

    fun startScan() {
        stopScan()
        error = null
        cameras = emptyList()
        stage = BtStage.List
        scanning = true
        scanJob = scope.launch {
            try {
                withTimeoutOrNull(40_000) {
                    scanGoPros(app).collect { cam ->
                        if (cameras.none { it.address == cam.address }) cameras = cameras + cam
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = describe(e)
            } finally {
                scanning = false
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        scanning = false
    }

    // ------------------------------------------------------------------ Conexión y emparejamiento

    fun connect(cam: FoundCamera) {
        stopScan()
        workJob?.cancel()
        error = null
        log = emptyList()
        cameraName = cam.name
        stage = BtStage.Working
        workJob = scope.launch {
            val b = GoProBle(app)
            try {
                step("Conectando con ${cam.name}…")
                delay(700) // tras dejar de buscar, Android necesita un momento antes de conectar
                b.connect(cam.device) { step(it) }
                step("Emparejando… la primera vez la cámara debe estar en modo de emparejamiento.")
                b.pair()
                step("Activando las notificaciones…")
                b.enableNotifications()
                ble = b
                stage = BtStage.Ready
                watch(b)
            } catch (e: CancellationException) {
                b.close()
                throw e
            } catch (e: Exception) {
                b.close()
                error = describe(e)
                stage = BtStage.List
            }
        }
    }

    private fun watch(b: GoProBle) {
        watchJob?.cancel()
        watchJob = scope.launch {
            b.disconnected.first { it }
            if (ble === b) {
                closeBle()
                error = "Se perdió la conexión Bluetooth con la cámara."
                stage = BtStage.List
            }
        }
    }

    /** Cancela lo que se esté haciendo y vuelve a un estado estable. */
    fun cancel() {
        workJob?.cancel()
        workJob = null
        error = null
        stage = if (ble != null) BtStage.Ready else BtStage.List
    }

    // ------------------------------------------------------------------ Usar el WiFi de la cámara

    fun useCameraWifi() {
        val b = ble ?: return
        if (!vm.wifiLink.wifiOn()) {
            error = "El WiFi de la TV está apagado (con cable de red no se puede encender). " +
                "Usa «Configurar red doméstica (COHN)»."
            return
        }
        error = null
        log = emptyList()
        stage = BtStage.Working
        workJob = scope.launch {
            try {
                step("Encendiendo el WiFi de la cámara…")
                b.enableWifiAp()
                delay(2_500)
                step("Leyendo el nombre y la clave del WiFi…")
                val (ssid, pass) = b.readWifiCredentials()
                apSsid = ssid
                apPass = pass
                var joined = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    step("Uniendo la TV a «$ssid»… acepta el aviso en pantalla si aparece.")
                    joined = try {
                        vm.wifiLink.connect(ssid, pass)
                        true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        false
                    }
                }
                if (!joined) {
                    stage = BtStage.ManualWifi
                    return@launch
                }
                finishAp(bind = false, retryStage = BtStage.Ready)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = describe(e)
                stage = if (ble != null) BtStage.Ready else BtStage.List
            }
        }
    }

    /** La TV ya se unió a mano al WiFi de la cámara. */
    fun continueManual() {
        error = null
        log = emptyList()
        stage = BtStage.Working
        workJob = scope.launch {
            try {
                finishAp(bind = true, retryStage = BtStage.ManualWifi)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = describe(e)
                stage = BtStage.ManualWifi
            }
        }
    }

    private suspend fun finishAp(bind: Boolean, retryStage: BtStage) {
        step("Conectando con la cámara…")
        val err = vm.tryConnect(Config(ConnMode.AP, "10.5.5.9", "", ""), bind)
        if (err == null) {
            closeBle()
        } else {
            error = err
            stage = retryStage
        }
    }

    // ------------------------------------------------------------------ Red doméstica (COHN)

    fun openHomeForm() {
        error = null
        stage = BtStage.HomeForm
    }

    fun setupHome() {
        val b = ble ?: return
        val ssid = homeSsid.trim()
        if (ssid.isEmpty()) {
            error = "Escribe el nombre de tu red WiFi."
            return
        }
        prefs.edit().putString("home_ssid", ssid).apply()
        error = null
        log = emptyList()
        stage = BtStage.Working
        workJob = scope.launch {
            try {
                step("Conectando la cámara a «$ssid»…")
                b.provisionWifi(ssid, homePass)
                step("La cámara ya está en tu red. Configurando el acceso (COHN)…")
                val creds = b.provisionCohn { step(it) }
                homePass = ""
                step("Listo. IP de la cámara: ${creds.ip}")
                closeBle()
                vm.wifiLink.release()
                val err = vm.tryConnect(Config(ConnMode.HOME, creds.ip, creds.user, creds.pass), true)
                if (err != null) {
                    error = "La cámara quedó configurada (IP ${creds.ip}) pero no pude conectarme: $err"
                    stage = BtStage.List
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = describe(e)
                stage = if (ble != null) BtStage.Ready else BtStage.List
            }
        }
    }

    // ------------------------------------------------------------------ Errores

    private fun describe(e: Throwable): String = when {
        e is SecurityException -> "Falta el permiso de Bluetooth. Concédelo e inténtalo de nuevo."
        e.message == "bluetooth_off" ->
            "El Bluetooth de la TV está apagado. Actívalo en Ajustes → Control remoto y accesorios."
        e.message?.startsWith("scan_failed") == true -> "No pude buscar por Bluetooth (${e.message})."
        e is BleConnectException ->
            "${e.message}. Se probó 3 veces. Comprueba que la cámara esté encendida y en modo de emparejamiento " +
                "(desliza a la izquierda → Conexiones → Conectar dispositivo → GoPro Quik App), que no esté " +
                "conectada a otro teléfono o app, y si «GoPro…» aparece en Ajustes → Control remoto y accesorios " +
                "de la TV, elimínala de ahí. Luego vuelve a buscar."
        e is TimeoutException ->
            (e.message ?: "Tiempo de espera agotado") +
                ". Si ya habías emparejado esta cámara, olvídala en los ajustes Bluetooth de la TV y repite con la " +
                "cámara en modo de emparejamiento."
        else -> e.message ?: e.javaClass.simpleName
    }
}
