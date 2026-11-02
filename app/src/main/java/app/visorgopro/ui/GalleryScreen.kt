package app.visorgopro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.visorgopro.AppVm
import app.visorgopro.Screen
import app.visorgopro.net.GoProClient
import app.visorgopro.net.MediaItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun GalleryScreen(vm: AppVm) {
    val client = vm.client ?: return
    LaunchedEffect(client) { vm.loadMedia() }

    var filter by remember { mutableIntStateOf(0) } // 0 todo, 1 videos, 2 fotos
    val all = vm.media
    val shown = all.orEmpty().filter {
        when (filter) {
            1 -> it.isVideo
            2 -> !it.isVideo
            else -> true
        }
    }
    val firstTab = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstTab.requestFocus() } }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Galería", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(1.dp))
            FocusButton("Todo", onClick = { filter = 0 }, selected = filter == 0, focusRequester = firstTab)
            FocusButton("Videos", onClick = { filter = 1 }, selected = filter == 1)
            FocusButton("Fotos", onClick = { filter = 2 }, selected = filter == 2)
            FocusButton("Actualizar", onClick = { vm.loadMedia(force = true) })
        }

        when {
            vm.mediaLoading && all == null ->
                Text("Leyendo la tarjeta de la cámara…", color = TextDim, fontSize = 20.sp)
            vm.mediaError != null ->
                Text(vm.mediaError ?: "", color = Danger, fontSize = 18.sp)
            all != null && shown.isEmpty() ->
                Text("No hay archivos para mostrar.", color = TextDim, fontSize = 20.sp)
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 230.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(shown, key = { it.path }) { item ->
                    MediaCell(client, item) {
                        vm.screen = if (item.isVideo) Screen.Video(item) else Screen.Photo(item)
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaCell(client: GoProClient, item: MediaItem, onClick: () -> Unit) {
    FocusCard(onClick = onClick, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
        CameraImage(client, client.thumbnailUrl(item), Modifier.fillMaxSize())
        if (item.isVideo) {
            Text(
                "▶",
                color = Color.White,
                fontSize = 34.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color(0xAA000000))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text(item.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(
                formatDate(item.created) + "  ·  " + formatSize(item.size) +
                    if (item.isGroup) "  ·  secuencia" else "",
                color = Color(0xFFD0D8E0),
                fontSize = 13.sp,
            )
        }
    }
}

private fun formatDate(epochSeconds: Long): String =
    SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(epochSeconds * 1000))

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.getDefault(), "%.1f GB", bytes / 1073741824.0)
    bytes >= 1L shl 20 -> String.format(Locale.getDefault(), "%.0f MB", bytes / 1048576.0)
    else -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0)
}
