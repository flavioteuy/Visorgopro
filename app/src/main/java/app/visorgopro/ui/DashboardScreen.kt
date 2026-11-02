package app.visorgopro.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.visorgopro.AppVm
import app.visorgopro.Screen
import java.util.Locale

@Composable
fun DashboardScreen(vm: AppVm) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    val info = vm.info
    val st = vm.state

    Column(
        Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(info?.model ?: "GoPro", color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Bold)

        val parts = ArrayList<String>()
        if (st?.battery != null) parts += "Batería ${st.battery}%"
        if (st?.sdRemainingKb != null) {
            val gb = st.sdRemainingKb!! / 1024.0 / 1024.0
            parts += String.format(Locale.getDefault(), "%.1f GB libres", gb)
        }
        if (st?.recording == true) parts += "● GRABANDO"
        if (!info?.firmware.isNullOrBlank()) parts += "Firmware ${info?.firmware}"
        Text(parts.joinToString("   ·   "), color = TextDim, fontSize = 20.sp)

        if (vm.lost) {
            Text("Se perdió la conexión con la cámara. Reintentando…", color = Danger, fontSize = 18.sp)
        }

        Spacer(Modifier.height(6.dp))

        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            MenuTile("Vista en vivo", "Ver y grabar en directo", first, Modifier.weight(1f)) {
                vm.screen = Screen.Live
            }
            MenuTile("Galería", "Videos y fotos de la tarjeta", null, Modifier.weight(1f)) {
                vm.screen = Screen.Gallery
            }
            MenuTile("Configuración", "Modo, resolución, FPS, lente…", null, Modifier.weight(1f)) {
                vm.screen = Screen.Settings
            }
        }

        FocusButton(text = "Desconectar", onClick = { vm.disconnect() }, danger = true)
    }
}

@Composable
private fun MenuTile(
    title: String,
    subtitle: String,
    focusRequester: FocusRequester?,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    FocusCard(onClick = onClick, modifier = modifier.height(220.dp), focusRequester = focusRequester) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start,
        ) {
            Text(title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(subtitle, color = TextDim, fontSize = 18.sp)
        }
    }
}
