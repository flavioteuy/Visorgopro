# Visor GoPro (Google TV)

App para Google TV / Android TV que se conecta a una GoPro HERO12 Black usando la API abierta de GoPro (Open GoPro, por HTTP) y permite:

- **Galería:** ver los videos y fotos guardados en la tarjeta de la cámara (miniaturas, reproducción por streaming, visor de fotos).
- **Vista en vivo:** vista previa en directo (MPEG-TS por UDP) con botón para grabar / disparar.
- **Configuración:** cambiar el modo de captura (presets), resolución, FPS, lente, HyperSmooth y anti parpadeo.

## Cómo conectar

**Por Bluetooth (recomendado):** pulsa «Conectar por Bluetooth». La app busca la cámara, la empareja (la primera vez, ponla en modo de emparejamiento: desliza a la izquierda → Conexiones → Conectar dispositivo → GoPro Quik App) y ofrece dos caminos:

- **Usar el WiFi de la cámara:** enciende el WiFi de la cámara, lee su nombre y clave y une la TV a esa red (Android pide confirmación). Si la TV no puede hacerlo sola, la app muestra el nombre y la clave para que la conectes a mano desde los ajustes de la TV. Mientras tanto la TV no tiene internet.
- **Configurar red doméstica (COHN):** escribes el WiFi de tu casa, la cámara se conecta a él y la app crea el acceso seguro (COHN). Se hace una sola vez: después, en el modo «Red doméstica», basta con pulsar Conectar (la IP y las credenciales quedan guardadas) y la TV conserva internet.

**A mano, modo «WiFi de la cámara»:** en la GoPro, desliza a la izquierda → Conexiones → Conectar dispositivo → GoPro Quik App; luego conecta la TV a esa red WiFi desde los ajustes de la TV y abre la app. (Si tu TV tiene Ethernet, la app fuerza su tráfico por el WiFi de la cámara.)

**A mano, modo «Red doméstica (COHN)»:** escribe la IP, el usuario y la contraseña de COHN de una cámara ya configurada.

## Control remoto

- Flechas / OK: navegar y elegir. Atrás: volver.
- En un video: **Menú** alterna entre el original y la versión ligera (LRV). Si la TV no puede decodificar el original (por ejemplo 5.3K), la app cambia sola a la versión ligera.
- En una foto: ◀ ▶ para pasar de foto.

## Compilar el APK

**En la nube (sin instalar nada):** la acción *Compilar APK* se ejecuta sola en cada push a `main`, deja `VisorGoPro-<versión>.apk` en la carpeta `releases/` del repositorio y también como artefacto de la ejecución. Si falla, el resumen del error queda en `releases/build-error.log`.

**En tu computadora:** abre la carpeta con Android Studio, o ejecuta `./gradlew assembleRelease` (JDK 17 + Android SDK 35). El APK queda en `app/build/outputs/apk/release/`.

Para instalarlo en la TV: activa las opciones de desarrollador y la depuración por red, y usa `adb connect <IP-de-la-TV>` + `adb install VisorGoPro-1.1.0.apk` (o cualquier app de «enviar archivos a la TV»).

## Firma

`signing.properties` y `keystore/visorgopro.jks` firman el APK de release y **no se suben al repositorio** (están en `.gitignore`). Mantén siempre la misma llave para poder actualizar sobre la instalación anterior.

- **Local:** deja esos dos archivos en la raíz del proyecto.
- **GitHub Actions:** crea los secretos `KEYSTORE_BASE64` (el `.jks` en base64: `base64 -w0 keystore/visorgopro.jks`) y `KEYSTORE_PASSWORD`. Sin ellos el APK se firma con una llave de debug temporal distinta en cada ejecución, y habría que desinstalar la app antes de instalar la siguiente versión.

## Notas técnicas

- Endpoints usados (Open GoPro): `gopro/camera/info`, `state`, `keep_alive`, `shutter/start|stop`, `setting?setting=&option=`, `presets/get`, `presets/load?id=`, `stream/start?port=8554`, `stream/stop`, `gopro/media/list`, `media/thumbnail`, `media/screennail`, y `videos/DCIM/<carpeta>/<archivo>`.
- Bluetooth LE (Open GoPro): servicio `0xFEA6`; comandos en `b5f90072/73`, consultas en `b5f90076/77` y gestión de red en `b5f90091/92`; el emparejamiento se dispara leyendo la clave del WiFi (`b5f90003`). Mensajes protobuf mínimos escritos a mano (sin librería) para `RequestConnectNew`, `RequestGetCOHNStatus`, `RequestCreateCOHNCert` y `RequestClearCOHNCert`.
- Con COHN la app confía en el certificado autofirmado de la cámara sin validarlo, y solo para la IP configurada (red doméstica).
- Kotlin + Jetpack Compose, Media3 (ExoPlayer) y OkHttp. `minSdk 26`.
