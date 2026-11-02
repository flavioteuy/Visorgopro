package app.visorgopro.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.visorgopro.AppVm
import app.visorgopro.ble.BtStage
import app.visorgopro.ble.blePermissions
import app.visorgopro.ble.hasBlePermissions

@Composable
fun BluetoothScreen(vm: AppVm) {
    val bt = vm.bt
    val ctx = LocalContext.current
    val first = remember { FocusRequester() }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.isNotEmpty() && grants.values.all { it }) {
            bt.startScan()
        } else {
            bt.error = "Sin el permiso de Bluetooth no puedo buscar la cámara."
        }
    }
    val scanOrAsk: () -> Unit = {
        if (hasBlePermissions(ctx)) bt.startScan() else launcher.launch(blePermissions())
    }

    LaunchedEffect(Unit) {
        if (bt.stage == BtStage.List && bt.cameras.isEmpty() && !bt.scanning) scanOrAsk()
    }
    LaunchedEffect(bt.stage, bt.cameras.isNotEmpty()) { runCatching { first.requestFocus() } }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 56.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Bluetooth", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)

        when (bt.stage) {
            BtStage.List -> {
                Text(
                    "La primera vez, pon la cámara en modo de emparejamiento: desliza a la izquierda → Conexiones → " +
                        "Conectar dispositivo → GoPro Quik App. Después aparecerá aquí.",
                    color = TextDim,
                    fontSize = 17.sp,
                    modifier = Modifier.widthIn(max = 900.dp),
                )
                if (bt.scanning) Text("Buscando cámaras…", color = Accent, fontSize = 20.sp)
                bt.cameras.forEachIndexed { i, cam ->
                    FocusButton(
                        text = "${cam.name}    (señal ${cam.rssi} dBm)",
                        onClick = { bt.connect(cam) },
                        focusRequester = if (i == 0) first else null,
                    )
                }
                if (!bt.scanning && bt.cameras.isEmpty() && bt.error == null) {
                    Text("No encontré ninguna cámara.", color = TextDim, fontSize = 18.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FocusButton("Buscar de nuevo", onClick = scanOrAsk)
                    FocusButton("Volver", onClick = { vm.back() })
                }
            }

            BtStage.Working -> {
                bt.log.forEach { Text(it, color = Color.White, fontSize = 19.sp) }
                FocusButton("Cancelar", onClick = { bt.cancel() }, focusRequester = first)
            }

            BtStage.Ready -> {
                // Con la TV por cable el WiFi suele estar apagado y no se puede encender: solo sirve la red doméstica
                val wifiOff = remember(bt.stage) { !vm.wifiLink.wifiOn() }
                Text("Conectada por Bluetooth: ${bt.cameraName}", color = Accent, fontSize = 22.sp)
                if (wifiOff) {
                    Text(
                        "El WiFi de esta TV está apagado (con cable de red no se puede encender), así que no puedo " +
                            "unirla al WiFi de la cámara. Usa la red doméstica: la cámara se conecta al WiFi de tu " +
                            "router y la TV, por cable, a ese mismo router.",
                        color = TextDim,
                        fontSize = 17.sp,
                        modifier = Modifier.widthIn(max = 900.dp),
                    )
                    FocusButton(
                        "Configurar red doméstica (COHN)",
                        onClick = { bt.openHomeForm() },
                        focusRequester = first,
                    )
                } else {
                    FocusButton("Usar el WiFi de la cámara", onClick = { bt.useCameraWifi() }, focusRequester = first)
                    Text(
                        "Enciende el WiFi de la cámara y une la TV a él. Android pedirá confirmación. " +
                            "Mientras tanto, la TV no tiene internet.",
                        color = TextDim,
                        fontSize = 16.sp,
                        modifier = Modifier.widthIn(max = 900.dp),
                    )
                    FocusButton("Configurar red doméstica (COHN)", onClick = { bt.openHomeForm() })
                    Text(
                        "La cámara se conecta a tu WiFi de casa y la TV la usa por la red, sin perder internet. " +
                            "Se hace una sola vez; después basta con «Conectar» en el modo de red doméstica.",
                        color = TextDim,
                        fontSize = 16.sp,
                        modifier = Modifier.widthIn(max = 900.dp),
                    )
                }
                FocusButton("Cerrar Bluetooth", onClick = { vm.back() })
            }

            BtStage.HomeForm -> {
                Text(
                    "Escribe los datos del WiFi de tu router (la cámara se conecta a él; la TV, por WiFi o por cable, debe estar " +
                        "en el mismo router). Mejor la banda de 2,4 GHz. La clave no se guarda.",
                    color = TextDim,
                    fontSize = 17.sp,
                    modifier = Modifier.widthIn(max = 900.dp),
                )
                OutlinedTextField(
                    value = bt.homeSsid,
                    onValueChange = { bt.homeSsid = it },
                    label = { Text("Nombre de tu WiFi") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth().focusRequester(first),
                )
                OutlinedTextField(
                    value = bt.homePass,
                    onValueChange = { bt.homePass = it },
                    label = { Text("Contraseña del WiFi") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FocusButton("Configurar la cámara", onClick = { bt.setupHome() })
                    FocusButton("Cancelar", onClick = { bt.cancel() })
                }
            }

            BtStage.ManualWifi -> {
                Text(
                    "No pude unir la TV automáticamente. Conéctala tú desde Ajustes → Red e Internet a esta red y " +
                        "vuelve aquí:",
                    color = TextDim,
                    fontSize = 17.sp,
                    modifier = Modifier.widthIn(max = 900.dp),
                )
                Text("Red:  ${bt.apSsid}", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("Clave:  ${bt.apPass}", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FocusButton("Ya me conecté, continuar", onClick = { bt.continueManual() }, focusRequester = first)
                    FocusButton("Cancelar", onClick = { bt.cancel() })
                }
            }
        }

        bt.error?.let {
            Text(it, color = Danger, fontSize = 18.sp, modifier = Modifier.widthIn(max = 900.dp))
        }
    }
}
