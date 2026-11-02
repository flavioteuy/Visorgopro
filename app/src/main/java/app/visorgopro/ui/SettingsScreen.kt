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
 * Ajustes de la HERO12 Black que se pueden cambiar por HTTP (gopro/camera/setting).
 * Si la cámara no acepta una opción con el modo actual, responde con las opciones válidas y la app las muestra.
 */
data class SettingDef(val id: Int, val label: String, val options: List<Pair<Int, String>>)

val SETTINGS: List<SettingDef> = listOf(
    SettingDef(
        2, "Resolución de video",
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
        121, "Lente de video",
        listOf(
            0 to "Ancho", 4 to "Lineal", 2 to "Estrecho", 3 to "SuperView", 7 to "Max SuperView",
            9 to "HyperView", 11 to "Max HyperView", 8 to "Lineal + nivelación del horizonte",
            10 to "Lineal + bloqueo del horizonte",
        ),
    ),
    SettingDef(
        135, "HyperSmooth",
        listOf(
            0 to "Desactivado", 100 to "Estándar", 1 to "Bajo", 2 to "Alto",
            3 to "Boost", 4 to "Auto Boost",
        ),
    ),
    SettingDef(
        134, "Anti parpadeo",
        listOf(3 to "50 Hz (PAL)", 2 to "60 Hz (NTSC)"),
    ),
)

private fun currentLabel(def: SettingDef, state: CameraState?): String {
    val v = state?.settings?.get(def.id) ?: return "—"
    return def.options.firstOrNull { it.first.toLong() == v }?.second ?: "Valor $v"
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
        Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Configuración", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SettingRow(
                    label = "Modo de captura (preset)",
                    value = if (vm.presets == null) "Cargando…" else "Cambiar",
                    onClick = { if (vm.presets != null) showPresets = true },
                    focusRequester = first,
                )
            }
            items(SETTINGS, key = { it.id }) { def ->
                SettingRow(
                    label = def.label,
                    value = currentLabel(def, vm.state),
                    onClick = { dialog = def },
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
