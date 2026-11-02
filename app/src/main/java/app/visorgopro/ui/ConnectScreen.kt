package app.visorgopro.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.visorgopro.AppVm
import app.visorgopro.net.ConnMode

@Composable
fun ConnectScreen(vm: AppVm) {
    val connectFocus = remember { FocusRequester() }
    val btFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { btFocus.requestFocus() } }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 56.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Visor GoPro", color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Bold)
        Text(
            "Mira la galería, la vista en vivo y los ajustes de tu GoPro desde la TV.",
            color = TextDim,
            fontSize = 18.sp,
        )
        Spacer(Modifier.height(6.dp))

        FocusButton(
            text = "Conectar por Bluetooth (recomendado)",
            onClick = { vm.openBluetooth() },
            focusRequester = btFocus,
        )
        Text(
            "Busca y empareja la cámara y se encarga del WiFi por ti. Si tu TV va por cable y no puede encender el WiFi, " +
                "usa esta opción y elige «Configurar red doméstica».",
            color = TextDim,
            fontSize = 16.sp,
            modifier = Modifier.widthIn(max = 900.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text("O conecta a mano:", color = TextDim, fontSize = 16.sp)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FocusButton(
                text = "WiFi de la cámara",
                selected = vm.mode == ConnMode.AP,
                onClick = { vm.selectMode(ConnMode.AP) },
            )
            FocusButton(
                text = "Red doméstica (COHN)",
                selected = vm.mode == ConnMode.HOME,
                onClick = { vm.selectMode(ConnMode.HOME) },
            )
        }

        if (vm.mode == ConnMode.AP) {
            Text(
                "1. En la GoPro: desliza a la izquierda → Conexiones → Conectar dispositivo → GoPro Quik App " +
                    "(la cámara queda emitiendo su WiFi).\n" +
                    "2. En la TV: Ajustes → Red e Internet y conéctate a la red de la cámara " +
                    "(el nombre y la contraseña salen en el menú Conexiones de la cámara).\n" +
                    "3. Vuelve aquí y pulsa Conectar. La TV no tendrá internet mientras esté en esa red.",
                color = TextDim,
                fontSize = 17.sp,
                modifier = Modifier.widthIn(max = 900.dp),
            )
        } else {
            Text(
                "Requiere que la cámara ya esté configurada como «Camera on the Home Network» (COHN) en tu WiFi; " +
                    "se hace una vez con el botón de Bluetooth. Después se guardan la IP y las credenciales y basta " +
                    "con pulsar Conectar.",
                color = TextDim,
                fontSize = 17.sp,
                modifier = Modifier.widthIn(max = 900.dp),
            )
            OutlinedTextField(
                value = vm.host,
                onValueChange = { vm.host = it },
                label = { Text("IP de la cámara") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(),
            )
            OutlinedTextField(
                value = vm.user,
                onValueChange = { vm.user = it },
                label = { Text("Usuario COHN") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(),
            )
            OutlinedTextField(
                value = vm.pass,
                onValueChange = { vm.pass = it },
                label = { Text("Contraseña COHN") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(),
            )
        }

        FocusButton(
            text = if (vm.connecting) "Conectando…" else "Conectar",
            onClick = { if (!vm.connecting) vm.connect() },
            focusRequester = connectFocus,
        )

        vm.error?.let {
            Text(it, color = Danger, fontSize = 18.sp, modifier = Modifier.widthIn(max = 900.dp))
        }
    }
}
