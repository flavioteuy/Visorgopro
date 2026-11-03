package app.visorgopro.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.visorgopro.AppVm
import app.visorgopro.net.CameraState

/**
 * Ajustes de la HERO12 Black que se pueden cambiar por HTTP (gopro/camera/setting). Los identificadores y valores
 * salen de la especificación oficial Open GoPro. Si la cámara no acepta una opción con el modo actual, responde con
 * las opciones válidas y la app las muestra.
 */
data class SettingDef(val id: Int, val label: String, val options: List<Pair<Int, String>>)

data class SettingGroup(val title: String, val defs: List<SettingDef>)

val SETTING_GROUPS: List<SettingGroup> = listOf(
    SettingGroup(
        "Video",
        listOf(
            SettingDef(
                2, "Resolución",
                listOf(
                    100 to "5.3K", 1 to "4K", 4 to "2.7K", 9 to "1080p",
                    27 to "5.3K 4:3", 18 to "4K 4:3", 6 to "2.7K 4:3",
                    26 to "5.3K 8:7", 28 to "4K 8:7",
                ),
            ),
            SettingDef(
                3, "Cuadros por segundo",
                listOf(
                    13 to "200", 0 to "240", 1 to "120", 2 to "100", 5 to "60",
                    6 to "50", 8 to "30", 9 to "25", 10 to "24",
                ),
            ),
            SettingDef(
                108, "Relación de aspecto",
                listOf(0 to "4:3", 1 to "16:9", 3 to "8:7", 4 to "9:16"),
            ),
            SettingDef(
                121, "Lente",
                listOf(
                    0 to "Ancho", 4 to "Lineal", 2 to "Estrecho", 3 to "SuperView", 7 to "Max SuperView",
                    9 to "HyperView", 11 to "Max HyperView", 8 to "Lineal + nivelación del horizonte",
                    10 to "Lineal + bloqueo del horizonte",
                ),
            ),
            SettingDef(
                135, "HyperSmooth (estabilización)",
                listOf(
                    0 to "Desactivado", 100 to "Estándar", 1 to "Bajo", 2 to "Alto",
                    3 to "Boost", 4 to "Auto Boost",
                ),
            ),
            SettingDef(
                173, "Modo de rendimiento",
                listOf(0 to "Máximo rendimiento", 1 to "Batería extendida", 2 to "Trípode / estacionario"),
            ),
            SettingDef(
                182, "Tasa de bits",
                listOf(0 to "Estándar", 1 to "Alta"),
            ),
            SettingDef(
                183, "Profundidad de color",
                listOf(0 to "8 bits", 2 to "10 bits"),
            ),
            SettingDef(
                184, "Perfil de color",
                listOf(0 to "Estándar", 1 to "HDR", 2 to "Log", 101 to "HLG HDR"),
            ),
            SettingDef(
                167, "HindSight (grabar hacia atrás)",
                listOf(4 to "Desactivado", 2 to "15 segundos", 3 to "30 segundos"),
            ),
            SettingDef(
                134, "Anti parpadeo",
                listOf(3 to "50 Hz (PAL)", 2 to "60 Hz (NTSC)"),
            ),
        ),
    ),
    SettingGroup(
        "Foto",
        listOf(
            SettingDef(
                125, "Salida de foto",
                listOf(0 to "Estándar", 3 to "SuperPhoto", 2 to "HDR", 1 to "RAW"),
            ),
            SettingDef(
                122, "Lente de foto",
                listOf(
                    31 to "Ancho 27 MP", 32 to "Lineal 27 MP", 27 to "Ancho 23 MP", 28 to "Lineal 23 MP",
                    0 to "Ancho 12 MP", 10 to "Lineal 12 MP", 19 to "Estrecho",
                ),
            ),
        ),
    ),
    SettingGroup(
        "Cámara",
        listOf(
            SettingDef(
                175, "Controles",
                listOf(0 to "Fácil", 1 to "Pro"),
            ),
            SettingDef(
                59, "Apagado automático",
                listOf(0 to "Nunca", 1 to "1 minuto", 4 to "5 minutos", 6 to "15 minutos", 7 to "30 minutos"),
            ),
            SettingDef(
                219, "Protector de pantalla",
                listOf(0 to "Nunca", 1 to "1 minuto", 2 to "2 minutos", 3 to "3 minutos", 4 to "5 minutos"),
            ),
            SettingDef(
                91, "Luces LED",
                listOf(3 to "Todas encendidas", 4 to "Todas apagadas", 5 to "Frontal apagada"),
            ),
            SettingDef(
                216, "Volumen de los pitidos",
                listOf(70 to "Bajo", 85 to "Medio", 100 to "Alto"),
            ),
            SettingDef(
                83, "GPS",
                listOf(0 to "Desactivado", 1 to "Activado"),
            ),
        ),
    ),
)

private fun currentLabel(def: SettingDef, state: CameraState?): String {
    val v = state?.settings?.get(def.id) ?: return "—"
    return def.options.firstOrNull { it.first.toLong() == v }?.second ?: "Valor $v"
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        color = Accent,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 2.sp,
        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp, start = 4.dp),
    )
}

@Composable
fun SettingsScreen(vm: AppVm) {
    var dialog by remember { mutableStateOf<SettingDef?>(null) }
    var showPresets by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        vm.message = null
        vm.loadPresets()
        runCatching { first.requestFocus() }
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Configuración", color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.SemiBold)

        val info = vm.info
        if (info != null) {
            val parts = listOfNotNull(
                info.model.takeIf { it.isNotBlank() },
                info.serial.takeIf { it.isNotBlank() }?.let { "Serie $it" },
                info.firmware.takeIf { it.isNotBlank() }?.let { "Firmware $it" },
                info.ssid.takeIf { it.isNotBlank() }?.let { "WiFi $it" },
            )
            if (parts.isNotEmpty()) {
                Text(parts.joinToString("  ·  "), color = TextDim, fontSize = 16.sp, fontWeight = FontWeight.Light)
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "h_modo") { SectionTitle("Modo de captura") }
            item(key = "preset") {
                SettingRow(
                    label = "Modo de captura (preset)",
                    value = if (vm.presets == null) "Cargando…" else "Cambiar",
                    onClick = { if (vm.presets != null) showPresets = true },
                    focusRequester = first,
                )
            }
            SETTING_GROUPS.forEach { group ->
                item(key = "h_" + group.title) { SectionTitle(group.title) }
                items(group.defs, key = { it.id }) { def ->
                    SettingRow(
                        label = def.label,
                        value = currentLabel(def, vm.state),
                        onClick = { dialog = def },
                    )
                }
            }
            item(key = "h_util") { SectionTitle("Utilidades") }
            item(key = "sync") {
                SettingRow(
                    label = "Sincronizar fecha y hora",
                    value = "Usar la de la TV",
                    onClick = { vm.syncTime() },
                )
            }
        }

        vm.message?.let {
            Text(it, color = if (vm.messageIsError) Danger else Accent, fontSize = 18.sp)
        }
    }

    dialog?.let { def ->
        val current = vm.state?.settings?.get(def.id)
        OptionDialog(
            title = def.label,
            options = def.options.map { it.second },
            selected = def.options.indexOfFirst { current != null && it.first.toLong() == current },
            onPick = { index ->
                vm.applySetting(def.id, def.options[index].first)
                dialog = null
            },
            onDismiss = { dialog = null },
        )
    }

    if (showPresets) {
        val list = vm.presets.orEmpty()
        OptionDialog(
            title = "Modo de captura",
            options = list.map { it.group + " · " + it.title },
            selected = -1,
            onPick = { index ->
                vm.applyPreset(list[index])
                showPresets = false
            },
            onDismiss = { showPresets = false },
        )
    }
}
