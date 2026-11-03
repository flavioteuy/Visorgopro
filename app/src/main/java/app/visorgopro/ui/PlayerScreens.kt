package app.visorgopro.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.SeekParameters
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.UdpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.PlayerView
import app.visorgopro.AppVm
import app.visorgopro.net.ImageLoader
import app.visorgopro.net.MediaItem
import app.visorgopro.net.friendlyError
import kotlinx.coroutines.delay
import androidx.media3.common.MediaItem as ExoMediaItem

// ====================================================================== Video guardado

private fun fmtTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0L) / 1000).toInt()
    val h = total / 3600
    val m = (total % 3600) / 60
    val sec = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}

/**
 * Reproductor de video con controles propios. El foco del control remoto se queda siempre en esta pantalla
 * (no en los botones internos del PlayerView, que desaparecen al ocultarse y dejaban la app sin respuesta).
 *
 *  OK / ▶❚❚ : pausa o reanuda     ◀ ▶ : saltar 10 s (mantén pulsado para saltar más)
 *  ▲ ▼ : mostrar los controles     Menú : original / versión ligera (LRV)
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun VideoScreen(vm: AppVm, item: MediaItem) {
    val client = vm.client ?: return
    val ctx = LocalContext.current
    val lrv = client.lrvUrl(item)
    var light by remember { mutableStateOf(false) }
    var notice by remember {
        mutableStateOf<String?>(if (lrv != null) "Menú: alternar versión ligera (LRV)" else null)
    }
    var seenTick by remember { mutableIntStateOf(vm.menuTick) }
    val focus = remember { FocusRequester() }

    var playing by remember { mutableStateOf(true) }
    var buffering by remember { mutableStateOf(true) }
    var ended by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var controlsVisible by remember { mutableStateOf(true) }
    var lastTouch by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var hasFocus by remember { mutableStateOf(false) }

    val exo = remember {
        ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(ctx).setDataSourceFactory(OkHttpDataSource.Factory(client.http))
            )
            .build()
            .also { it.setSeekParameters(SeekParameters.CLOSEST_SYNC) }
    }

    fun touch() {
        controlsVisible = true
        lastTouch = System.currentTimeMillis()
    }

    fun togglePlay() {
        if (exo.playbackState == Player.STATE_ENDED) {
            exo.seekTo(0L)
            exo.playWhenReady = true
        } else {
            exo.playWhenReady = !exo.playWhenReady
        }
        touch()
    }

    fun seekBy(deltaMs: Long) {
        val d = exo.duration
        val max = if (d == C.TIME_UNSET) Long.MAX_VALUE else d
        val target = (exo.currentPosition + deltaMs).coerceIn(0L, max)
        exo.seekTo(target)
        position = target
        touch()
    }

    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                // Si la TV no puede decodificar el video original (p. ej. 5.3K), se pasa a la versión ligera.
                if (!light && lrv != null &&
                    tracks.containsType(C.TRACK_TYPE_VIDEO) &&
                    !tracks.isTypeSupported(C.TRACK_TYPE_VIDEO)
                ) {
                    notice = "La TV no puede decodificar este video: usando la versión ligera (LRV)."
                    light = true
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (!light && lrv != null) {
                    notice = "No se pudo reproducir el original: usando la versión ligera (LRV)."
                    light = true
                } else {
                    notice = "No se pudo reproducir el video (${error.errorCodeName})."
                }
            }
        }
        exo.addListener(listener)
        onDispose {
            exo.removeListener(listener)
            exo.release()
        }
    }

    // Botón Menú del control remoto: alterna original / versión ligera.
    LaunchedEffect(vm.menuTick) {
        if (vm.menuTick != seenTick) {
            seenTick = vm.menuTick
            if (lrv != null) {
                light = !light
                notice = if (light) "Versión ligera (LRV)." else "Versión original."
            }
        }
    }

    LaunchedEffect(light) {
        val url = if (light && lrv != null) lrv else client.fileUrl(item)
        val resume = if (exo.currentMediaItem == null) 0L else exo.currentPosition
        exo.setMediaItem(ExoMediaItem.fromUri(url), resume)
        exo.prepare()
        exo.playWhenReady = true
    }

    LaunchedEffect(notice) {
        if (notice != null) {
            delay(5000)
            notice = null
        }
    }

    // Estado del reproductor para dibujar los controles, y ocultarlos tras unos segundos sin tocar nada.
    LaunchedEffect(exo) {
        while (true) {
            position = exo.currentPosition
            duration = exo.duration.takeIf { it != C.TIME_UNSET } ?: 0L
            buffering = exo.playbackState == Player.STATE_BUFFERING
            ended = exo.playbackState == Player.STATE_ENDED
            playing = exo.playWhenReady && !ended
            if (controlsVisible && playing && !buffering && System.currentTimeMillis() - lastTouch > 4000) {
                controlsVisible = false
            }
            if (ended || !exo.playWhenReady) controlsVisible = true
            delay(250)
        }
    }

    // El foco no debe perderse nunca: si se pierde por cualquier motivo, se recupera.
    LaunchedEffect(Unit) {
        while (true) {
            if (!hasFocus) runCatching { focus.requestFocus() }
            delay(500)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .onFocusChanged { hasFocus = it.isFocused }
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val repeats = ev.nativeKeyEvent.repeatCount
                val step = when {
                    repeats > 15 -> 60_000L
                    repeats > 5 -> 30_000L
                    else -> 10_000L
                }
                when (ev.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Spacebar, Key.MediaPlayPause -> {
                        if (repeats == 0) togglePlay()
                        true
                    }
                    Key.MediaPlay -> {
                        exo.playWhenReady = true
                        touch()
                        true
                    }
                    Key.MediaPause -> {
                        exo.playWhenReady = false
                        touch()
                        true
                    }
                    Key.DirectionRight, Key.MediaFastForward -> {
                        seekBy(step)
                        true
                    }
                    Key.DirectionLeft, Key.MediaRewind -> {
                        seekBy(-step)
                        true
                    }
                    Key.DirectionUp, Key.DirectionDown -> {
                        touch()
                        true
                    }
                    else -> false
                }
            }
            .focusable(),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                PlayerView(c).apply {
                    player = exo
                    useController = false
                    isFocusable = false
                    isFocusableInTouchMode = false
                    keepScreenOn = true
                }
            },
        )

        if (buffering) {
            Text(
                "Cargando…",
                color = Color.White,
                fontSize = 24.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0x99000000), RoundedCornerShape(10.dp))
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }

        val shownNotice = notice
        if (shownNotice != null) {
            Text(
                shownNotice,
                color = Color.White,
                fontSize = 18.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(24.dp)
                    .background(Color(0xAA000000), RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }

        if (controlsVisible) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))
                    )
                    .padding(horizontal = 48.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        if (ended) "↺" else if (playing) "▶" else "❚❚",
                        color = Accent,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "${fmtTime(position)} / ${fmtTime(duration)}",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        item.name + if (light) "  (versión ligera)" else "",
                        color = TextDim,
                        fontSize = 17.sp,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                }
                val fraction = if (duration > 0) (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0x55FFFFFF))
                ) {
                    Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(Accent))
                }
                Text(
                    "OK pausa / reanuda    ◀ ▶ saltar 10 s (mantén para más)" +
                        if (lrv != null) "    Menú: original / ligera" else "",
                    color = TextDim,
                    fontSize = 16.sp,
                )
            }
        }
    }
}

// ====================================================================== Foto

@Composable
fun PhotoScreen(vm: AppVm, item: MediaItem) {
    val client = vm.client ?: return
    val photos = remember(vm.media) { vm.media.orEmpty().filter { !it.isVideo } }
    var index by remember { mutableIntStateOf(photos.indexOfFirst { it.path == item.path }.coerceAtLeast(0)) }
    val current = photos.getOrNull(index) ?: item
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val bmp by produceState<Bitmap?>(null, current) {
        // Primero la versión rápida (screennail), luego el archivo completo reducido.
        value = ImageLoader.load(client, client.screennailUrl(current), 1280)
        value = ImageLoader.load(client, client.fileUrl(current), 2048) ?: value
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            // onKeyEvent va antes de focusable() para recibir las teclas cuando este elemento tiene el foco.
            .onKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown) {
                    when (ev.key) {
                        Key.DirectionRight -> {
                            if (index < photos.lastIndex) index++
                            true
                        }
                        Key.DirectionLeft -> {
                            if (index > 0) index--
                            true
                        }
                        else -> false
                    }
                } else {
                    false
                }
            }
            .focusable(),
    ) {
        val b = bmp
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else {
            Text("Cargando…", color = Color.White, fontSize = 22.sp, modifier = Modifier.align(Alignment.Center))
        }
        Text(
            "${current.name}   ◀ ▶ para cambiar de foto",
            color = Color.White,
            fontSize = 16.sp,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(24.dp)
                .background(Color(0x99000000))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

// ====================================================================== Vista en vivo

private fun stateName(state: Int): String = when (state) {
    Player.STATE_IDLE -> "inactivo"
    Player.STATE_BUFFERING -> "cargando"
    Player.STATE_READY -> "listo"
    Player.STATE_ENDED -> "terminado"
    else -> "?"
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun LiveScreen(vm: AppVm) {
    val client = vm.client ?: return
    val ctx = LocalContext.current
    var status by remember { mutableStateOf("Iniciando vista en vivo…") }
    var hasPicture by remember { mutableStateOf(false) }
    var diag by remember { mutableStateOf("") }
    val bytes = remember { AtomicLong(0L) }
    val videoMime = remember { mutableStateOf("") }
    val lastError = remember { mutableStateOf("") }
    val recFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { recFocus.requestFocus() } }

    val exo = remember {
        ExoPlayer.Builder(ctx)
            // Búfer mínimo para tener la menor latencia posible.
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(500, 2000, 250, 500).build())
            .build()
    }

    fun previewSource(): MediaSource {
        val extractors = DefaultExtractorsFactory().setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
        )
        val counter = object : TransferListener {
            override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
            override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
            override fun onBytesTransferred(
                source: DataSource,
                dataSpec: DataSpec,
                isNetwork: Boolean,
                bytesTransferred: Int,
            ) {
                bytes.addAndGet(bytesTransferred.toLong())
            }

            override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
        }
        // La cámara envía MPEG-TS por UDP al puerto 8554 del equipo que pidió la vista previa.
        val factory = DataSource.Factory {
            UdpDataSource(UdpDataSource.DEFAULT_MAX_PACKET_SIZE, 30_000).also { it.addTransferListener(counter) }
        }
        return ProgressiveMediaSource.Factory(factory, extractors)
            .createMediaSource(ExoMediaItem.fromUri("udp://0.0.0.0:8554"))
    }

    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    hasPicture = true
                    status = ""
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                val fmt = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.length > 0 }
                    ?.getTrackFormat(0)
                if (fmt != null) {
                    videoMime.value = fmt.sampleMimeType.orEmpty() + if (fmt.width > 0) " ${fmt.width}x${fmt.height}" else ""
                    if (!tracks.isTypeSupported(C.TRACK_TYPE_VIDEO)) {
                        status = "La TV no puede decodificar el video de la cámara (${fmt.sampleMimeType})."
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                hasPicture = false
                lastError.value = error.errorCodeName + (error.cause?.let { " · ${it.javaClass.simpleName}" } ?: "")
            }
        }
        exo.addListener(listener)
        onDispose {
            exo.removeListener(listener)
            exo.release()
            vm.stopStream()
        }
    }

    // Bucle de control: abre el socket UDP, pide la transmisión a la cámara y, si no llega imagen, lo repite.
    LaunchedEffect(client) {
        var attempt = 0
        while (isActive) {
            attempt++
            bytes.set(0L)
            hasPicture = false
            lastError.value = ""
            exo.setMediaSource(previewSource())
            exo.prepare()
            exo.playWhenReady = true
            delay(400)
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
                delay(4000)
                continue
            }
            status = "Esperando la señal de la cámara… (intento $attempt)"
            var waited = 0
            while (waited < 15_000 && !hasPicture && lastError.value.isEmpty()) {
                delay(250)
                waited += 250
            }
            while (isActive && hasPicture) delay(500)
            if (isActive) {
                status = if (bytes.get() == 0L) {
                    "No llegan datos de la cámara. Reintentando…"
                } else {
                    "Llegan datos pero no se pudo mostrar la imagen. Reintentando…"
                }
                delay(1500)
            }
        }
    }

    // Línea de diagnóstico: permite saber si la cámara envía datos y en qué punto se atasca.
    LaunchedEffect(exo) {
        while (true) {
            diag = buildString {
                append("UDP: ${bytes.get() / 1024} KB recibidos · reproductor: ${stateName(exo.playbackState)}")
                if (videoMime.value.isNotEmpty()) append(" · ${videoMime.value}")
                if (lastError.value.isNotEmpty()) append(" · error: ${lastError.value}")
            }
            delay(500)
        }
    }

    val recording = vm.state?.recording == true

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                PlayerView(c).apply {
                    player = exo
                    useController = false
                    isFocusable = false
                    keepScreenOn = true
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
                    Text(status, color = Color.White, fontSize = 22.sp)
                }
                Text(diag, color = TextDim, fontSize = 15.sp)
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
