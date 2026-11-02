package app.visorgopro.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch
import androidx.media3.common.MediaItem as ExoMediaItem

// ====================================================================== Video guardado

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

    val exo = remember {
        ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(ctx).setDataSourceFactory(OkHttpDataSource.Factory(client.http))
            )
            .build()
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
                    notice = "No se pudo reproducir el video."
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

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                PlayerView(c).apply {
                    player = exo
                    useController = true
                    controllerShowTimeoutMs = 4000
                    keepScreenOn = true
                    requestFocus()
                }
            },
        )
        val shownNotice = notice
        if (shownNotice != null) {
            Text(
                shownNotice,
                color = Color.White,
                fontSize = 18.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(24.dp)
                    .background(Color(0xAA000000))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
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

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun LiveScreen(vm: AppVm) {
    val client = vm.client ?: return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Iniciando vista en vivo…") }
    var hasPicture by remember { mutableStateOf(false) }
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
        // La cámara envía MPEG-TS por UDP al puerto 8554 del equipo que pidió la vista previa.
        return ProgressiveMediaSource.Factory(DataSource.Factory { UdpDataSource() }, extractors)
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

            override fun onPlayerError(error: PlaybackException) {
                hasPicture = false
                status = "Sin señal de la cámara. Reintentando…"
                scope.launch {
                    delay(1500)
                    runCatching {
                        exo.setMediaSource(previewSource())
                        exo.prepare()
                    }
                }
            }
        }
        exo.addListener(listener)
        onDispose {
            exo.removeListener(listener)
            exo.release()
            vm.stopStream()
        }
    }

    LaunchedEffect(client) {
        try {
            // Primero se abre el socket UDP y después se le pide a la cámara que empiece a transmitir.
            exo.setMediaSource(previewSource())
            exo.prepare()
            exo.playWhenReady = true
            delay(300)
            client.streamStart(8554)
            status = "Esperando la señal de la cámara…"
        } catch (e: Exception) {
            status = "No se pudo iniciar la vista en vivo: " + friendlyError(e)
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

        if (!hasPicture && status.isNotEmpty()) {
            Text(
                status,
                color = Color.White,
                fontSize = 22.sp,
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )
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
