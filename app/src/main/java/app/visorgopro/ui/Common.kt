package app.visorgopro.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Typography
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.visorgopro.R
import app.visorgopro.net.GoProClient
import app.visorgopro.net.ImageLoader

val Bg = Color(0xFF0B0F14)

/** Paneles semitransparentes: dejan ver el fondo degradado de la app. */
val Panel = Color(0xD9151C24)
val PanelSolid = Color(0xFF151C24)
val PanelBorder = Color(0xFF2A3A4B)
val Accent = Color(0xFF2EA8FF)
val Good = Color(0xFF3DD6A3)
val Warn = Color(0xFFFFB454)
val TextDim = Color(0xFFA8B5C2)
val Danger = Color(0xFFE5484D)

/** Tipografía de la app: Poppins (licencia OFL, ver licenses/Poppins-OFL.txt). */
val AppFont = FontFamily(
    Font(R.font.poppins_light, FontWeight.Light),
    Font(R.font.poppins_regular, FontWeight.Normal),
    Font(R.font.poppins_medium, FontWeight.Medium),
    Font(R.font.poppins_semibold, FontWeight.SemiBold),
    Font(R.font.poppins_bold, FontWeight.Bold),
)

private fun appTypography(): Typography {
    val t = Typography()
    return Typography(
        displayLarge = t.displayLarge.copy(fontFamily = AppFont),
        displayMedium = t.displayMedium.copy(fontFamily = AppFont),
        displaySmall = t.displaySmall.copy(fontFamily = AppFont),
        headlineLarge = t.headlineLarge.copy(fontFamily = AppFont),
        headlineMedium = t.headlineMedium.copy(fontFamily = AppFont),
        headlineSmall = t.headlineSmall.copy(fontFamily = AppFont),
        titleLarge = t.titleLarge.copy(fontFamily = AppFont),
        titleMedium = t.titleMedium.copy(fontFamily = AppFont),
        titleSmall = t.titleSmall.copy(fontFamily = AppFont),
        bodyLarge = t.bodyLarge.copy(fontFamily = AppFont),
        bodyMedium = t.bodyMedium.copy(fontFamily = AppFont),
        bodySmall = t.bodySmall.copy(fontFamily = AppFont),
        labelLarge = t.labelLarge.copy(fontFamily = AppFont),
        labelMedium = t.labelMedium.copy(fontFamily = AppFont),
        labelSmall = t.labelSmall.copy(fontFamily = AppFont),
    )
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            background = Bg,
            surface = PanelSolid,
            onSurface = Color.White,
            onBackground = Color.White,
        ),
        typography = appTypography(),
    ) {
        ProvideTextStyle(
            TextStyle(fontFamily = AppFont, color = Color.White, letterSpacing = 0.1.sp),
            content,
        )
    }
}

/** Fondo de toda la app: degradado oscuro con resplandores de color y anillos que recuerdan a un objetivo. */
@Composable
fun AppBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = modifier
            .background(
                Brush.verticalGradient(listOf(Color(0xFF0A1017), Color(0xFF0D1824), Color(0xFF090E14)))
            )
            .drawBehind {
                val w = size.width
                val h = size.height
                val glowA = Offset(w * 0.88f, h * 0.10f)
                drawCircle(
                    brush = Brush.radialGradient(listOf(Color(0x662EA8FF), Color.Transparent), center = glowA, radius = w * 0.46f),
                    radius = w * 0.46f,
                    center = glowA,
                )
                val glowB = Offset(w * 0.06f, h * 0.98f)
                drawCircle(
                    brush = Brush.radialGradient(listOf(Color(0x4420D3B5), Color.Transparent), center = glowB, radius = w * 0.40f),
                    radius = w * 0.40f,
                    center = glowB,
                )
                val lens = Offset(w * 0.86f, h * 0.46f)
                for (i in 1..6) {
                    drawCircle(
                        color = Color(if (i % 2 == 0) 0x12FFFFFF else 0x0AFFFFFF),
                        radius = h * 0.13f * i,
                        center = lens,
                        style = Stroke(width = if (i == 1) 3f else 2f),
                    )
                }
            },
        content = content,
    )
}

/** Tarjeta enfocable con D-pad: se agranda y muestra un borde al recibir el foco. */
@Composable
fun FocusCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, label = "focusScale")
    val shape = RoundedCornerShape(14.dp)
    var m = modifier.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
    if (focusRequester != null) m = m.focusRequester(focusRequester)
    Box(
        modifier = m
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(Panel)
            .border(if (focused) 3.dp else 1.dp, if (focused) Accent else PanelBorder, shape)
            .clickable(onClick = onClick),
        content = content,
    )
}

@Composable
fun FocusButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    danger: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    var m = modifier
    if (focusRequester != null) m = m.focusRequester(focusRequester)
    val bg = when {
        focused -> Accent
        selected -> Color(0xFF1D3A52)
        else -> Panel
    }
    val fg = when {
        focused -> Color.Black
        danger -> Danger
        else -> Color.White
    }
    Box(
        modifier = m
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(bg)
            .border(1.dp, if (selected) Accent else PanelBorder, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = fg, fontSize = 18.sp, fontWeight = FontWeight.Medium)
    }
}

/** Fila de ajuste: etiqueta a la izquierda, valor actual a la derecha. */
@Composable
fun SettingRow(
    label: String,
    value: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    FocusCard(onClick = onClick, modifier = Modifier.fillMaxWidth(), focusRequester = focusRequester) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp)) {
            Text(label, color = Color.White, fontSize = 20.sp, modifier = Modifier.align(Alignment.CenterStart))
            Text(value, color = Accent, fontSize = 20.sp, modifier = Modifier.align(Alignment.CenterEnd))
        }
    }
}

/** Imagen de la cámara (miniatura, screennail o archivo completo) con caché. */
@Composable
fun CameraImage(
    client: GoProClient,
    url: String,
    modifier: Modifier = Modifier,
    maxDim: Int = 480,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val bmp by produceState<Bitmap?>(null, client, url) {
        value = ImageLoader.load(client, url, maxDim)
    }
    val b = bmp
    if (b != null) {
        Image(
            bitmap = b.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            contentScale = contentScale,
        )
    } else {
        Box(modifier.background(Color(0xFF1A232D)))
    }
}

/** Diálogo con una lista de opciones navegable con D-pad. */
@Composable
fun OptionDialog(
    title: String,
    options: List<String>,
    selected: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .widthIn(min = 460.dp, max = 680.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(PanelSolid)
                .padding(24.dp)
        ) {
            Text(title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
            LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(options) { index, label ->
                    FocusButton(
                        text = label,
                        selected = index == selected,
                        onClick = { onPick(index) },
                        modifier = Modifier.fillMaxWidth(),
                        focusRequester = if (index == 0) first else null,
                    )
                }
            }
        }
    }
}
