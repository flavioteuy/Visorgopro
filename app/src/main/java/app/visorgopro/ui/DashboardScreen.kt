package app.visorgopro.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
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
        Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 44.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(
            info?.model ?: "GoPro",
            color = Color.White,
            fontSize = 46.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.5).sp,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val battery = st?.battery
            if (battery != null) {
                val color = when {
                    battery >= 40 -> Good
                    battery >= 15 -> Warn
                    else -> Danger
                }
                Chip("Batería $battery%", color)
            }
            val sd = st?.sdRemainingKb
            if (sd != null) {
                val gb = sd / 1024.0 / 1024.0
                Chip(String.format(Locale.getDefault(), "%.1f GB libres", gb), Accent)
            }
            if (st?.recording == true) Chip("GRABANDO", Danger)
            if (!info?.firmware.isNullOrBlank()) Chip("Firmware ${info?.firmware}", TextDim)
        }

        if (vm.lost) {
            Text("Se perdió la conexión con la cámara. Reintentando…", color = Danger, fontSize = 18.sp)
        }

        Spacer(Modifier.height(4.dp))

        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            MenuTile(TileIcon.Live, "Vista en vivo", "Ver y grabar en directo", first, Modifier.weight(1f)) {
                vm.screen = Screen.Live
            }
            MenuTile(TileIcon.Gallery, "Galería", "Videos y fotos de la tarjeta", null, Modifier.weight(1f)) {
                vm.screen = Screen.Gallery
            }
            MenuTile(TileIcon.Settings, "Configuración", "Modo, resolución, FPS, lente y más", null, Modifier.weight(1f)) {
                vm.screen = Screen.Settings
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            FocusButton(text = "Desconectar", onClick = { vm.disconnect() }, danger = true)
            Text("Flechas para moverte · OK para elegir · Atrás para volver", color = TextDim, fontSize = 16.sp)
        }
    }
}

/** Etiqueta redondeada con un punto de color. */
@Composable
private fun Chip(text: String, color: Color) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0x33FFFFFF))
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(color))
        Text(text, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
    }
}

private enum class TileIcon { Live, Gallery, Settings }

@Composable
private fun MenuTile(
    icon: TileIcon,
    title: String,
    subtitle: String,
    focusRequester: FocusRequester?,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    FocusCard(onClick = onClick, modifier = modifier.height(250.dp), focusRequester = focusRequester) {
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.Start,
        ) {
            Canvas(Modifier.size(72.dp)) { drawTileIcon(icon) }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = TextDim, fontSize = 18.sp, fontWeight = FontWeight.Light)
            }
        }
    }
}

private fun DrawScope.drawTileIcon(icon: TileIcon) {
    val w = size.width
    val h = size.height
    val line = w * 0.07f
    when (icon) {
        TileIcon.Live -> {
            drawCircle(Accent, radius = w * 0.44f, center = Offset(w / 2, h / 2), style = Stroke(line))
            drawCircle(Danger, radius = w * 0.22f, center = Offset(w / 2, h / 2))
        }
        TileIcon.Gallery -> {
            drawRoundRect(
                Accent,
                topLeft = Offset(w * 0.04f, h * 0.14f),
                size = Size(w * 0.92f, h * 0.72f),
                cornerRadius = CornerRadius(w * 0.1f),
                style = Stroke(line),
            )
            drawCircle(Warn, radius = w * 0.07f, center = Offset(w * 0.30f, h * 0.36f))
            val mountain = Path().apply {
                moveTo(w * 0.12f, h * 0.78f)
                lineTo(w * 0.40f, h * 0.50f)
                lineTo(w * 0.58f, h * 0.68f)
                lineTo(w * 0.72f, h * 0.54f)
                lineTo(w * 0.88f, h * 0.78f)
            }
            drawPath(mountain, Accent, style = Stroke(line, cap = StrokeCap.Round))
        }
        TileIcon.Settings -> {
            val rows = listOf(0.24f to 0.68f, 0.50f to 0.30f, 0.76f to 0.58f)
            for ((y, knob) in rows) {
                drawLine(
                    Accent,
                    start = Offset(w * 0.06f, h * y),
                    end = Offset(w * 0.94f, h * y),
                    strokeWidth = line,
                    cap = StrokeCap.Round,
                )
                drawCircle(Color(0xFF0B0F14), radius = w * 0.11f, center = Offset(w * knob, h * y))
                drawCircle(Accent, radius = w * 0.11f, center = Offset(w * knob, h * y), style = Stroke(line))
            }
        }
    }
}
