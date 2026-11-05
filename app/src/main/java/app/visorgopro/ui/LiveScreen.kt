package app.visorgopro.ui

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Process
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.visorgopro.AppVm
import app.visorgopro.net.friendlyError
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Receptor de la vista previa de la cámara. La cámara manda video MPEG-TS por UDP; aquí se recibe, se separa el
 * video (H.264 o H.265) y se decodifica directamente con MediaCodec sobre una Surface, sin ExoPlayer. Así no
 * dependemos de cómo ExoPlayer interprete los tiempos del flujo y se muestra cada cuadro en cuanto llega.
 */
class LiveStream(private val port: Int = 8554) {
    val bytes = AtomicLong(0L)
    val frames = AtomicLong(0L)
    private val dropped = AtomicLong(0L)

    @Volatile
    private var lastFrameAt = 0L

    @Volatile
    var surface: Surface? = null

    /** Tamaño del video decodificado, para mantener la proporción en pantalla. */
    @Volatile
    var videoWidth = 0

    @Volatile
    var videoHeight = 0

    @Volatile
    private var lastError = ""

    @Volatile
    private var decoderInfo = "decodificador sin iniciar"

    @Volatile
    private var streamTypes: List<Int> = emptyList()

    @Volatile
    private var firstByte = -1

    @Volatile
    private var tsPackets = 0L

    @Volatile
    private var running = false
    private var thread: Thread? = null

    // Estado del demultiplexor (solo lo toca el hilo de recepción)
    private var pmtPid = -1
    private var videoPid = -1

    @Volatile
    private var videoType = 0
    private val au = ByteArrayOutputStream(1 shl 18)
    private var auPts = -1L
    private var auActive = false
    private var badDatagrams = 0L
    private var fallbackPts = 0L

    // Decodificador (se crea y se libera desde el hilo de recepción)
    @Volatile
    private var codec: MediaCodec? = null
    private var codecSurface: Surface? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ receiveLoop() }, "live-udp").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(2_000)
        thread = null
    }

    fun resetCounters() {
        bytes.set(0L)
        frames.set(0L)
        dropped.set(0L)
        lastFrameAt = 0L
        lastError = ""
    }

    /** Hay imagen si se decodificó un cuadro hace menos de 3 segundos. */
    fun hasPicture(): Boolean = frames.get() > 0 && System.currentTimeMillis() - lastFrameAt < 3_000

    fun summary(): String = buildString {
        append("UDP: ${bytes.get() / 1024} KB")
        val types = streamTypes
        when {
            firstByte < 0 -> append(" · sin datos")
            tsPackets == 0L -> append(" · no parece MPEG-TS (primer byte 0x%02X)".format(firstByte))
            types.isEmpty() -> append(" · TS sin tabla de programa aún")
            else -> append(" · TS: " + types.joinToString(" + ") { typeName(it) })
        }
        append("\n$decoderInfo")
        if (videoWidth > 0) append(" · ${videoWidth}x$videoHeight")
        append(" · cuadros: ${frames.get()} · descartados: ${dropped.get()}")
        if (lastError.isNotEmpty()) append("\nError: $lastError")
    }

    private fun typeName(t: Int): String = when (t) {
        0x1B -> "H.264"
        0x24 -> "H.265"
        0x02 -> "MPEG-2 video"
        0x0F -> "AAC"
        0x11 -> "AAC-LATM"
        0x03, 0x04 -> "audio MPEG"
        0x06 -> "datos"
        0x15 -> "metadatos"
        else -> "tipo 0x%02X".format(t)
    }

    // ------------------------------------------------------------------ Recepción

    private fun receiveLoop() {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) }
        var opened: DatagramSocket? = null
        var tries = 0
        while (running && opened == null) {
            try {
                opened = DatagramSocket(null).apply {
                    reuseAddress = true
                    receiveBufferSize = 4 shl 20
                    soTimeout = 1_000
                    bind(InetSocketAddress(port))
                }
            } catch (e: Exception) {
                lastError = "no se pudo abrir el puerto UDP $port: ${e.message}"
                if (++tries > 20) return
                Thread.sleep(500)
            }
        }
        val sock = opened ?: return
        val buf = ByteArray(4096)
        val pkt = DatagramPacket(buf, buf.size)
        try {
            while (running) {
                pkt.length = buf.size
                try {
                    sock.receive(pkt)
                } catch (e: SocketTimeoutException) {
                    continue
                }
                val n = pkt.length
                bytes.addAndGet(n.toLong())
                if (firstByte < 0 && n > 0) firstByte = buf[0].toInt() and 0xFF
                handleDatagram(buf, n)
            }
        } catch (e: Exception) {
            if (running) lastError = "UDP: ${e.javaClass.simpleName}: ${e.message}"
        } finally {
            runCatching { sock.close() }
            releaseDecoder()
        }
    }

    /** Posición del primer paquete TS dentro del datagrama (0, tras una cabecera RTP de 12 bytes, o buscando). */
    private fun tsOffset(d: ByteArray, n: Int): Int {
        val sync = 0x47.toByte()
        if (n >= 188 && d[0] == sync && (n < 376 || d[188] == sync)) return 0
        if (n >= 12 + 188 && (d[0].toInt() and 0xC0) == 0x80 && d[12] == sync) return 12
        var p = 1
        while (p < minOf(n - 188, 64)) {
            if (d[p] == sync && (p + 188 >= n || d[p + 188] == sync)) return p
            p++
        }
        return -1
    }

    private fun handleDatagram(d: ByteArray, n: Int) {
        val off = tsOffset(d, n)
        if (off < 0) {
            badDatagrams++
            return
        }
        var p = off
        while (p + 188 <= n) {
            if (d[p] != 0x47.toByte()) {
                badDatagrams++
                break
            }
            packet(d, p)
            tsPackets++
            p += 188
        }
    }

    // ------------------------------------------------------------------ MPEG-TS

    private fun packet(d: ByteArray, o: Int) {
        val b1 = d[o + 1].toInt()
        val pid = ((b1 and 0x1F) shl 8) or (d[o + 2].toInt() and 0xFF)
        val pusi = (b1 and 0x40) != 0
        val afc = (d[o + 3].toInt() shr 4) and 3
        if ((afc and 1) == 0) return
        var p = o + 4
        if ((afc and 2) != 0) p += 1 + (d[o + 4].toInt() and 0xFF)
        val end = o + 188
        if (p >= end) return
        if (pid == 0) {
            if (pusi) parsePat(d, p, end)
        } else if (pid == pmtPid) {
            if (pusi) parsePmt(d, p, end)
        } else if (pid == videoPid) {
            video(d, p, end, pusi)
        }
    }

    private fun parsePat(d: ByteArray, p: Int, end: Int) {
        val s = p + 1 + (d[p].toInt() and 0xFF)
        if (s + 8 > end || d[s].toInt() != 0) return
        val len = ((d[s + 1].toInt() and 0x0F) shl 8) or (d[s + 2].toInt() and 0xFF)
        val last = minOf(s + 3 + len - 4, end)
        var e = s + 8
        while (e + 4 <= last) {
            val program = ((d[e].toInt() and 0xFF) shl 8) or (d[e + 1].toInt() and 0xFF)
            if (program != 0) {
                pmtPid = ((d[e + 2].toInt() and 0x1F) shl 8) or (d[e + 3].toInt() and 0xFF)
                return
            }
            e += 4
        }
    }

    private fun parsePmt(d: ByteArray, p: Int, end: Int) {
        val s = p + 1 + (d[p].toInt() and 0xFF)
        if (s + 12 > end || d[s].toInt() != 2) return
        val len = ((d[s + 1].toInt() and 0x0F) shl 8) or (d[s + 2].toInt() and 0xFF)
        val last = minOf(s + 3 + len - 4, end)
        val infoLen = ((d[s + 10].toInt() and 0x0F) shl 8) or (d[s + 11].toInt() and 0xFF)
        var e = s + 12 + infoLen
        val types = ArrayList<Int>()
        var vPid = -1
        var vType = 0
        while (e + 5 <= last) {
            val type = d[e].toInt() and 0xFF
            val pid = ((d[e + 1].toInt() and 0x1F) shl 8) or (d[e + 2].toInt() and 0xFF)
            val esInfo = ((d[e + 3].toInt() and 0x0F) shl 8) or (d[e + 4].toInt() and 0xFF)
            types += type
            if (vPid < 0 && (type == 0x1B || type == 0x24)) {
                vPid = pid
                vType = type
            }
            e += 5 + esInfo
        }
        if (types.isEmpty()) return
        streamTypes = types
        if (vPid >= 0 && (vPid != videoPid || vType != videoType)) {
            videoPid = vPid
            videoType = vType
            auActive = false
            au.reset()
            releaseDecoder()
        }
    }

    private fun readPts(d: ByteArray, o: Int): Long =
        ((d[o].toLong() and 0x0E) shl 29) or
            ((d[o + 1].toLong() and 0xFF) shl 22) or
            ((d[o + 2].toLong() and 0xFE) shl 14) or
            ((d[o + 3].toLong() and 0xFF) shl 7) or
            ((d[o + 4].toLong() and 0xFE) shr 1)

    private fun video(d: ByteArray, p: Int, end: Int, pusi: Boolean) {
        if (pusi) {
            finishAccessUnit()
            // Cabecera PES: 00 00 01 id largo(2) banderas(2) largoCabecera [PTS…]
            if (p + 9 > end || d[p].toInt() != 0 || d[p + 1].toInt() != 0 || d[p + 2].toInt() != 1) return
            val ptsFlag = (d[p + 7].toInt() shr 6) and 3
            val headerLen = d[p + 8].toInt() and 0xFF
            auPts = if (ptsFlag >= 2 && p + 14 <= end) readPts(d, p + 9) else -1L
            au.reset()
            auActive = true
            val start = p + 9 + headerLen
            if (start < end) au.write(d, start, end - start)
        } else if (auActive) {
            au.write(d, p, end - p)
        }
    }

    private fun finishAccessUnit() {
        if (!auActive) return
        auActive = false
        if (au.size() == 0) return
        val data = au.toByteArray()
        au.reset()
        onAccessUnit(data, auPts)
    }

    // ------------------------------------------------------------------ Decodificación

    /** True si la unidad de acceso trae un cuadro clave (IDR) o los parámetros SPS/PPS (VPS/SPS en H.265). */
    private fun hasKeyframe(data: ByteArray): Boolean {
        val hevc = videoType == 0x24
        var i = 0
        val limit = data.size - 4
        while (i < limit) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                val header = when {
                    data[i + 2].toInt() == 1 -> data[i + 3].toInt()
                    data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1 && i + 4 < data.size -> data[i + 4].toInt()
                    else -> -1
                }
                if (header >= 0) {
                    val type = if (hevc) (header shr 1) and 0x3F else header and 0x1F
                    val key = if (hevc) type == 19 || type == 20 || type == 32 || type == 33 else type == 5 || type == 7
                    if (key) return true
                    i += 3
                    continue
                }
            }
            i++
        }
        return false
    }

    private fun onAccessUnit(data: ByteArray, pts: Long) {
        val surf = surface
        if (codec != null && codecSurface !== surf) releaseDecoder()
        if (codec == null) {
            if (surf == null || !surf.isValid || !hasKeyframe(data)) {
                dropped.incrementAndGet()
                return
            }
            startDecoder(surf)
            if (codec == null) return
        }
        queue(data, pts)
    }

    private fun startDecoder(surf: Surface) {
        try {
            val mime = if (videoType == 0x24) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
            val format = MediaFormat.createVideoFormat(mime, 1280, 720)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            val c = MediaCodec.createDecoderByType(mime)
            c.configure(format, surf, null, 0)
            c.start()
            codec = c
            codecSurface = surf
            decoderInfo = "decodificador ${mime.removePrefix("video/")} en marcha"
            Thread({ outputLoop(c) }, "live-out").start()
        } catch (e: Exception) {
            lastError = "decodificador: ${e.javaClass.simpleName}: ${e.message}"
            releaseDecoder()
        }
    }

    private fun queue(data: ByteArray, pts: Long) {
        val c = codec ?: return
        if (data.size > MAX_INPUT) {
            dropped.incrementAndGet()
            return
        }
        try {
            val i = c.dequeueInputBuffer(8_000)
            if (i < 0) {
                dropped.incrementAndGet()
                return
            }
            val input = c.getInputBuffer(i) ?: return
            input.clear()
            input.put(data)
            val us = if (pts >= 0) pts * 100 / 9 else fallbackPts++ * 33_333
            c.queueInputBuffer(i, 0, data.size, us, 0)
        } catch (e: Exception) {
            lastError = "decodificador: ${e.javaClass.simpleName}: ${e.message}"
            releaseDecoder()
        }
    }

    private fun outputLoop(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (running && codec === c) {
                val i = c.dequeueOutputBuffer(info, 10_000)
                if (i >= 0) {
                    c.releaseOutputBuffer(i, true) // se muestra de inmediato
                    frames.incrementAndGet()
                    lastFrameAt = System.currentTimeMillis()
                } else if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    runCatching {
                        val f = c.outputFormat
                        videoWidth = f.getInteger(MediaFormat.KEY_WIDTH)
                        videoHeight = f.getInteger(MediaFormat.KEY_HEIGHT)
                    }
                }
            }
        } catch (e: Exception) {
            if (codec === c) lastError = "salida: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun releaseDecoder() {
        val c = codec
        codec = null
        codecSurface = null
        if (c != null) {
            runCatching { c.stop() }
            runCatching { c.release() }
        }
        decoderInfo = "decodificador sin iniciar"
    }

    private companion object {
        const val MAX_INPUT = 1 shl 20
    }
}

@Composable
fun LiveScreen(vm: AppVm) {
    val client = vm.client ?: return
    var status by remember { mutableStateOf("Iniciando vista en vivo…") }
    var hasPicture by remember { mutableStateOf(false) }
    var diag by remember { mutableStateOf("") }
    var aspect by remember { mutableFloatStateOf(16f / 9f) }
    val recFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { recFocus.requestFocus() } }

    val stream = remember { LiveStream(8554) }

    // El socket UDP se abre antes de pedirle la transmisión a la cámara.
    DisposableEffect(stream) {
        stream.start()
        onDispose {
            stream.stop()
            vm.stopStream()
        }
    }

    // Bucle de control: pide la transmisión y, si no aparece imagen, la detiene y la vuelve a pedir.
    LaunchedEffect(client) {
        var attempt = 0
        while (isActive) {
            attempt++
            stream.resetCounters()
            delay(300)
            try {
                if (attempt > 1) {
                    runCatching { client.streamStop() }
                    delay(500)
                }
                client.streamStart(8554)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "No se pudo iniciar la vista en vivo: " + friendlyError(e)
                delay(4_000)
                continue
            }
            status = "Esperando la imagen de la cámara… (intento $attempt)"
            var waited = 0
            while (waited < 12_000 && !stream.hasPicture()) {
                delay(250)
                waited += 250
            }
            while (isActive && stream.hasPicture()) delay(500)
            if (isActive) {
                status = if (stream.bytes.get() == 0L) {
                    "No llegan datos de la cámara. Reintentando…"
                } else {
                    "Llegan datos pero no se pudo mostrar la imagen. Reintentando…"
                }
                delay(1_500)
            }
        }
    }

    // Diagnóstico y proporción de la imagen
    LaunchedEffect(stream) {
        while (true) {
            hasPicture = stream.hasPicture()
            diag = stream.summary()
            if (stream.videoWidth > 0 && stream.videoHeight > 0) {
                aspect = stream.videoWidth.toFloat() / stream.videoHeight.toFloat()
            }
            delay(500)
        }
    }

    val recording = vm.state?.recording == true

    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = Modifier.aspectRatio(aspect),
            factory = { c ->
                SurfaceView(c).apply {
                    keepScreenOn = true
                    isFocusable = false
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            stream.surface = holder.surface
                        }

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            stream.surface = holder.surface
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            stream.surface = null
                        }
                    })
                }
            },
        )

        if (!hasPicture) {
            Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (status.isNotEmpty()) {
                    Text(status, color = Color.White, fontSize = 22.sp, textAlign = TextAlign.Center)
                }
                Text(diag, color = TextDim, fontSize = 15.sp, textAlign = TextAlign.Center)
            }
        }

        Row(
            Modifier
                .align(Alignment.BottomStart)
                .padding(32.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FocusButton(
                text = if (recording) "■ Detener" else "● Grabar / Disparar",
                onClick = { vm.toggleShutter() },
                danger = recording,
                focusRequester = recFocus,
            )
            val battery = vm.state?.battery
            val info = buildString {
                if (recording) append("● GRABANDO   ")
                if (battery != null) append("Batería $battery%   ")
                if (vm.lost) append("Conexión perdida")
            }
            if (info.isNotBlank()) {
                Text(
                    info,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.background(Color(0xAA000000)).padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}
