package app.visorgopro

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.visorgopro.ble.BluetoothFlow
import app.visorgopro.net.CameraInfo
import app.visorgopro.net.CameraState
import app.visorgopro.net.Config
import app.visorgopro.net.ConnMode
import app.visorgopro.net.GoProClient
import app.visorgopro.net.MediaItem
import app.visorgopro.net.NetworkBinder
import app.visorgopro.net.Preset
import app.visorgopro.net.WifiLink
import app.visorgopro.net.friendlyError
import app.visorgopro.net.settingError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Connect : Screen
    data object Bluetooth : Screen
    data object Dashboard : Screen
    data object Live : Screen
    data object Gallery : Screen
    data object Settings : Screen
    data class Video(val item: MediaItem) : Screen
    data class Photo(val item: MediaItem) : Screen
}

class AppVm(private val app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("visorgopro", Context.MODE_PRIVATE)

    /** Une la TV al WiFi de la cámara (Android 10 o superior) cuando se hace por Bluetooth. */
    val wifiLink = WifiLink(app)

    /** Flujo de la pantalla Bluetooth (búsqueda, emparejamiento, WiFi de la cámara y COHN). */
    val bt: BluetoothFlow by lazy { BluetoothFlow(app, viewModelScope, this, prefs) }

    // ---- Datos de conexión (editables en la pantalla de conexión)
    var mode by mutableStateOf(
        runCatching { ConnMode.valueOf(prefs.getString("mode", "AP") ?: "AP") }.getOrDefault(ConnMode.AP)
    )
    var host by mutableStateOf(prefs.getString("host", "10.5.5.9") ?: "10.5.5.9")
    var user by mutableStateOf(prefs.getString("user", "") ?: "")
    var pass by mutableStateOf(prefs.getString("pass", "") ?: "")

    // ---- Estado general
    var screen by mutableStateOf<Screen>(Screen.Connect)
    var client by mutableStateOf<GoProClient?>(null)
    var info by mutableStateOf<CameraInfo?>(null)
    var state by mutableStateOf<CameraState?>(null)
    var connecting by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var lost by mutableStateOf(false)

    /** Se incrementa cada vez que se pulsa el botón Menú del control remoto. */
    var menuTick by mutableIntStateOf(0)

    // ---- Galería
    var media by mutableStateOf<List<MediaItem>?>(null)
    var mediaLoading by mutableStateOf(false)
    var mediaError by mutableStateOf<String?>(null)

    // ---- Configuración
    var presets by mutableStateOf<List<Preset>?>(null)
    var message by mutableStateOf<String?>(null)
    var messageIsError by mutableStateOf(false)

    private fun say(text: String?, isError: Boolean = false) {
        message = text
        messageIsError = isError
    }

    private var pollJob: Job? = null

    // ---------------------------------------------------------------- Conexión

    fun selectMode(newMode: ConnMode) {
        if (mode == newMode) return
        mode = newMode
        host = if (newMode == ConnMode.AP) "10.5.5.9" else prefs.getString("home_host", "") ?: ""
        error = null
    }

    fun openBluetooth() {
        error = null
        bt.reset()
        screen = Screen.Bluetooth
    }

    fun connect() {
        val cfg = Config(mode, host.trim(), user.trim(), pass)
        if (cfg.host.isEmpty()) {
            error = "Escribe la IP de la cámara."
            return
        }
        connecting = true
        error = null
        viewModelScope.launch {
            error = tryConnect(cfg, bind = true)
            connecting = false
        }
    }

    /**
     * Conecta con la cámara y abre el panel. Devuelve el mensaje de error, o null si salió bien.
     * [bind] indica si hay que dirigir el tráfico de la app por el WiFi (modo cámara) o liberarlo (red doméstica).
     */
    suspend fun tryConnect(cfg: Config, bind: Boolean): String? {
        val editor = prefs.edit()
        editor.putString("mode", cfg.mode.name)
        editor.putString("host", cfg.host)
        editor.putString("user", cfg.user)
        editor.putString("pass", cfg.pass)
        if (cfg.mode == ConnMode.HOME) editor.putString("home_host", cfg.host)
        editor.apply()
        mode = cfg.mode
        host = cfg.host
        user = cfg.user
        pass = cfg.pass

        return try {
            if (cfg.mode == ConnMode.AP) {
                if (bind) NetworkBinder.bindToWifi(app)
            } else {
                NetworkBinder.unbind(app)
            }
            val c = GoProClient(cfg)
            val i = c.info()
            val s = c.state()
            client = c
            info = i
            state = s
            lost = false
            media = null
            presets = null
            screen = Screen.Dashboard
            startPolling()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            friendlyError(e)
        }
    }

    fun disconnect() {
        pollJob?.cancel()
        pollJob = null
        val c = client
        if (c != null) {
            viewModelScope.launch { runCatching { c.streamStop() } }
        }
        client = null
        info = null
        state = null
        media = null
        presets = null
        wifiLink.release()
        NetworkBinder.unbind(app)
        screen = Screen.Connect
    }

    fun back() {
        when (screen) {
            is Screen.Video, is Screen.Photo -> screen = Screen.Gallery
            Screen.Live, Screen.Gallery, Screen.Settings -> screen = Screen.Dashboard
            Screen.Dashboard -> disconnect()
            Screen.Bluetooth -> {
                bt.close()
                wifiLink.release()
                screen = Screen.Connect
            }
            Screen.Connect -> Unit
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            var failures = 0
            while (isActive) {
                delay(3000)
                val c = client ?: break
                try {
                    c.keepAlive()
                    val s = screen
                    if (s is Screen.Dashboard || s is Screen.Live || s is Screen.Settings) {
                        state = c.state()
                    }
                    failures = 0
                    lost = false
                } catch (e: Exception) {
                    failures++
                    if (failures >= 3) lost = true
                }
            }
        }
    }

    // ---------------------------------------------------------------- Vista en vivo

    fun stopStream() {
        val c = client ?: return
        viewModelScope.launch { runCatching { c.streamStop() } }
    }

    fun toggleShutter() {
        val c = client ?: return
        val recording = state?.recording == true
        viewModelScope.launch {
            try {
                c.shutter(!recording)
                delay(600)
                state = c.state()
            } catch (e: Exception) {
                say(friendlyError(e), true)
            }
        }
    }

    // ---------------------------------------------------------------- Galería

    fun loadMedia(force: Boolean = false) {
        val c = client ?: return
        if (mediaLoading || (media != null && !force)) return
        mediaLoading = true
        mediaError = null
        viewModelScope.launch {
            try {
                media = c.mediaList()
            } catch (e: Exception) {
                mediaError = friendlyError(e)
            }
            mediaLoading = false
        }
    }

    // ---------------------------------------------------------------- Configuración

    fun loadPresets() {
        val c = client ?: return
        if (presets != null) return
        viewModelScope.launch {
            try {
                presets = c.presets()
            } catch (e: Exception) {
                say(friendlyError(e), true)
            }
        }
    }

    fun applySetting(settingId: Int, option: Int) {
        val c = client ?: return
        message = null
        viewModelScope.launch {
            try {
                c.setSetting(settingId, option)
                state = c.state()
                say("Ajuste aplicado.")
            } catch (e: Exception) {
                say(settingError(e), true)
            }
        }
    }

    fun syncTime() {
        val c = client ?: return
        message = null
        viewModelScope.launch {
            try {
                c.setDateTime()
                say("Fecha y hora de la cámara actualizadas con las de la TV.")
            } catch (e: Exception) {
                say(settingError(e), true)
            }
        }
    }

    fun applyPreset(preset: Preset) {
        val c = client ?: return
        message = null
        viewModelScope.launch {
            try {
                c.loadPreset(preset.id)
                delay(500)
                state = c.state()
                say("Modo cambiado: ${preset.group} · ${preset.title}")
            } catch (e: Exception) {
                say(settingError(e), true)
            }
        }
    }

    override fun onCleared() {
        bt.close()
        wifiLink.release()
        super.onCleared()
    }
}
