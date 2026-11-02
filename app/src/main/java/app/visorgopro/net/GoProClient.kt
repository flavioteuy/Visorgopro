package app.visorgopro.net

import android.annotation.SuppressLint
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Cliente HTTP de la API abierta de GoPro (Open GoPro).
 *
 * - Modo AP: http://10.5.5.9:8080 (la TV está conectada al WiFi de la cámara).
 * - Modo HOME (COHN): https://<ip> con autenticación básica y certificado propio de la cámara.
 */
class GoProClient(val config: Config) {

    val baseUrl: String =
        if (config.mode == ConnMode.HOME) "https://${config.host}" else "http://${config.host}:8080"

    val http: OkHttpClient = buildClient()

    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private fun buildClient(): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
        if (config.mode == ConnMode.HOME) {
            // La cámara usa un certificado autofirmado: solo se acepta para el host configurado.
            val trust = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf(trust), SecureRandom())
            b.sslSocketFactory(ctx.socketFactory, trust)
            b.hostnameVerifier { host, _ -> host == config.host }
            val cred = Credentials.basic(config.user, config.pass)
            b.addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("Authorization", cred).build())
            }
        }
        return b.build()
    }

    // ---------------------------------------------------------------- URLs

    fun thumbnailUrl(item: MediaItem): String = "$baseUrl/gopro/media/thumbnail?path=${item.path}"

    fun screennailUrl(item: MediaItem): String = "$baseUrl/gopro/media/screennail?path=${item.path}"

    fun fileUrl(item: MediaItem): String = "$baseUrl/videos/DCIM/${item.folder}/${item.name}"

    fun lrvUrl(item: MediaItem): String? =
        item.lrvName?.let { "$baseUrl/videos/DCIM/${item.folder}/$it" }

    // ---------------------------------------------------------------- HTTP

    private suspend fun getJson(pathAndQuery: String, slow: Boolean = false): JSONObject =
        withContext(Dispatchers.IO) {
            val client = if (slow) http.newBuilder().readTimeout(90, TimeUnit.SECONDS).build() else http
            val req = Request.Builder().url("$baseUrl/$pathAndQuery").build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw GoProHttpException(resp.code, body)
                if (body.isBlank()) JSONObject() else JSONObject(body)
            }
        }

    // ---------------------------------------------------------------- Cámara

    suspend fun info(): CameraInfo {
        val o = getJson("gopro/camera/info")
        val i = o.optJSONObject("info") ?: o
        return CameraInfo(
            model = i.optString("model_name", "GoPro"),
            firmware = i.optString("firmware_version", ""),
            serial = i.optString("serial_number", ""),
            ssid = i.optString("ap_ssid", ""),
        )
    }

    suspend fun state(): CameraState {
        val o = getJson("gopro/camera/state")
        return CameraState(
            status = o.optJSONObject("status").toLongMap(),
            settings = o.optJSONObject("settings").toLongMap(),
        )
    }

    suspend fun keepAlive() {
        getJson("gopro/camera/keep_alive")
    }

    suspend fun shutter(on: Boolean) {
        getJson("gopro/camera/shutter/" + if (on) "start" else "stop")
    }

    suspend fun setSetting(id: Int, option: Int) {
        getJson("gopro/camera/setting?setting=$id&option=$option")
    }

    suspend fun loadPreset(id: Int) {
        getJson("gopro/camera/presets/load?id=$id")
    }

    suspend fun presets(): List<Preset> {
        val o = getJson("gopro/camera/presets/get")
        val groups = o.optJSONArray("presetGroupArray")
            ?: o.optJSONArray("preset_group_array")
            ?: return emptyList()
        val out = ArrayList<Preset>()
        for (i in 0 until groups.length()) {
            val g = groups.getJSONObject(i)
            val groupName = prettyGroup(g.opt("id")?.toString().orEmpty())
            val arr = g.optJSONArray("presetArray") ?: g.optJSONArray("preset_array") ?: continue
            for (j in 0 until arr.length()) {
                val p = arr.getJSONObject(j)
                if (!p.optBoolean("isVisible", p.optBoolean("is_visible", true))) continue
                val custom = p.optString("customName", p.optString("custom_name", ""))
                val title = if (custom.isNotBlank()) {
                    custom
                } else {
                    prettyTitle(p.opt("titleId") ?: p.opt("title_id"), p.optInt("id"))
                }
                out += Preset(p.optInt("id"), title, groupName)
            }
        }
        return out
    }

    // ---------------------------------------------------------------- Vista en vivo

    suspend fun streamStart(port: Int = 8554) {
        getJson("gopro/camera/stream/start?port=$port")
    }

    suspend fun streamStop() {
        getJson("gopro/camera/stream/stop")
    }

    // ---------------------------------------------------------------- Galería

    suspend fun mediaList(): List<MediaItem> {
        val o = getJson("gopro/media/list", slow = true)
        val media = o.optJSONArray("media") ?: return emptyList()
        val out = ArrayList<MediaItem>()
        for (i in 0 until media.length()) {
            val dir = media.getJSONObject(i)
            val folder = dir.optString("d")
            val files = dir.optJSONArray("fs") ?: continue
            for (j in 0 until files.length()) {
                val f = files.getJSONObject(j)
                val name = f.optString("n")
                val ext = name.substringAfterLast('.', "").uppercase()
                val isVideo = ext == "MP4"
                val isPhoto = ext == "JPG"
                if (!isVideo && !isPhoto) continue
                out += MediaItem(
                    folder = folder,
                    name = name,
                    created = f.optString("cre", "0").toLongOrNull() ?: 0L,
                    size = f.optString("s", "0").toLongOrNull() ?: 0L,
                    isVideo = isVideo,
                    isGroup = f.has("g"),
                    hasLrv = f.has("glrv"),
                )
            }
        }
        return out.sortedByDescending { it.created }
    }
}

// -------------------------------------------------------------------- Utilidades

private fun JSONObject?.toLongMap(): Map<Int, Long> {
    val out = HashMap<Int, Long>()
    if (this == null) return out
    val iter = keys()
    while (iter.hasNext()) {
        val k = iter.next()
        val id = k.toIntOrNull() ?: continue
        out[id] = optLong(k)
    }
    return out
}

private fun prettyGroup(raw: String): String = when (raw) {
    "PRESET_GROUP_ID_VIDEO", "1000" -> "Video"
    "PRESET_GROUP_ID_PHOTO", "1001" -> "Foto"
    "PRESET_GROUP_ID_TIMELAPSE", "1002" -> "Timelapse"
    else -> raw.removePrefix("PRESET_GROUP_ID_").lowercase().replace('_', ' ')
        .replaceFirstChar { it.uppercase() }
}

private val titleNames = mapOf(
    "ACTIVITY" to "Actividad",
    "STANDARD" to "Estándar",
    "CINEMATIC" to "Cinemático",
    "PHOTO" to "Foto",
    "LIVE_BURST" to "Ráfaga en vivo",
    "BURST" to "Ráfaga",
    "NIGHT" to "Nocturno",
    "TIME_WARP" to "TimeWarp",
    "TIME_LAPSE" to "Time lapse",
    "NIGHT_LAPSE" to "Night lapse",
    "SLOMO" to "Cámara lenta",
    "VIDEO" to "Video",
    "LOOPING" to "Bucle",
    "HDR" to "HDR",
    "BASIC" to "Básico",
    "CUSTOM" to "Personalizado",
    "ULTRA_SLO_MO" to "Ultra cámara lenta",
    "HIGHEST_QUALITY" to "Máxima calidad",
    "EXTENDED_BATTERY" to "Batería extendida",
    "LONGEST_BATTERY" to "Máxima batería",
    "FULL_FRAME" to "Cuadro completo",
)

private fun prettyTitle(raw: Any?, presetId: Int): String {
    if (raw is String && raw.startsWith("PRESET_TITLE_")) {
        val key = raw.removePrefix("PRESET_TITLE_")
        return titleNames[key]
            ?: key.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
    return "Preset $presetId"
}

fun friendlyError(e: Throwable): String = when (e) {
    is GoProHttpException -> when (e.code) {
        401 -> "Usuario o contraseña incorrectos."
        403 -> "La cámara rechazó la solicitud (403)."
        404 -> "La cámara no soporta esta función (404)."
        else -> "La cámara respondió con error ${e.code}."
    }
    is javax.net.ssl.SSLException ->
        "Falló la conexión segura con la cámara."
    is java.io.IOException ->
        "No se pudo conectar con la cámara. Revisa que la TV esté en su red WiFi y que la cámara tenga el WiFi encendido."
    else -> e.message ?: e.javaClass.simpleName
}

/** Mensaje para un ajuste rechazado: la cámara devuelve las opciones válidas si la combinación no existe. */
fun settingError(e: Throwable): String {
    if (e is GoProHttpException) {
        try {
            val o = JSONObject(e.body)
            val sup = o.optJSONArray("supported_options")
            if (sup != null && sup.length() > 0) {
                val names = (0 until sup.length()).map { sup.getJSONObject(it).optString("display_name") }
                return "No disponible con la configuración actual. Opciones válidas: " +
                    names.joinToString(", ")
            }
        } catch (ignored: Exception) {
            // el cuerpo no era JSON: se usa el mensaje genérico
        }
    }
    return friendlyError(e)
}
