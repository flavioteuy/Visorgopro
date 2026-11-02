package app.visorgopro.net

enum class ConnMode { AP, HOME }

data class Config(
    val mode: ConnMode,
    val host: String,
    val user: String,
    val pass: String,
)

data class CameraInfo(
    val model: String,
    val firmware: String,
    val serial: String,
    val ssid: String,
)

data class MediaItem(
    val folder: String,
    val name: String,
    val created: Long,
    val size: Long,
    val isVideo: Boolean,
    val isGroup: Boolean,
    /** La cámara informó que existe una versión de baja resolución (LRV) de este video. */
    val hasLrv: Boolean,
) {
    val path: String get() = "$folder/$name"

    /** Nombre del archivo de baja resolución (LRV) asociado a un video, si existe. */
    val lrvName: String?
        get() = if (hasLrv && isVideo && (name.startsWith("GX") || name.startsWith("GH")) && name.contains('.')) {
            "GL" + name.substring(2).substringBeforeLast('.') + ".LRV"
        } else {
            null
        }
}

data class CameraState(
    val status: Map<Int, Long>,
    val settings: Map<Int, Long>,
) {
    /** Estado 70: porcentaje de batería interna. */
    val battery: Long? get() = status[70]

    /** Estado 10: la cámara está grabando / capturando. */
    val recording: Boolean get() = status[10] == 1L

    /** Estado 54: espacio libre en la tarjeta SD (KB). */
    val sdRemainingKb: Long? get() = status[54]
}

data class Preset(
    val id: Int,
    val title: String,
    val group: String,
)

class GoProHttpException(val code: Int, val body: String) : Exception("HTTP $code")
