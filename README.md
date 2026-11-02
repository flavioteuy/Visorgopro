# Visor GoPro (Google TV)

App para Google TV / Android TV que se conecta a una GoPro HERO12 Black usando la API abierta de GoPro (Open GoPro, por HTTP) y permite:

- **Galería:** ver los videos y fotos guardados en la tarjeta de la cámara (miniaturas, reproducción por streaming, visor de fotos).
- **Vista en vivo:** vista previa en directo (MPEG-TS por UDP) con botón para grabar / disparar.
- **Configuración:** cambiar el modo de captura (presets), resolución, FPS, lente, HyperSmooth y anti parpadeo.

## Cómo conectar

**Modo «WiFi de la cámara»** (el más simple): en la GoPro, desliza a la izquierda → Conexiones → Conectar dispositivo → GoPro Quik App; luego conecta la TV a esa red WiFi desde los ajustes de la TV y abre la app. (Durante ese tiempo la TV no tiene internet; si tu TV tiene Ethernet, la app fuerza su tráfico por el WiFi de la cámara.)

**Modo «Red doméstica (COHN)»**: la cámara y la TV en tu misma WiFi. Requiere haber configurado antes en la cámara «Camera on the Home Network» (se hace por Bluetooth, p. ej. con el SDK de Open GoPro) y copiar aquí IP, usuario y contraseña. La app todavía **no** hace ese emparejamiento por Bluetooth.

## Control remoto

- Flechas / OK: navegar y elegir. Atrás: volver.
- En un video: **Menú** alterna entre el original y la versión ligera (LRV). Si la TV no puede decodificar el original (por ejemplo 5.3K), la app cambia sola a la versión ligera.
- En una foto: ◀ ▶ para pasar de foto.

## Compilar el APK

**En la nube (sin instalar nada):** sube esta carpeta a un repositorio **privado** de GitHub (incluye la llave de firma). La acción *Compilar APK* se ejecuta sola en cada push y deja `VisorGoPro-<versión>.apk` en *Actions → la ejecución → Artifacts*.

**En tu computadora:** abre la carpeta con Android Studio, o ejecuta `./gradlew assembleRelease` (JDK 17 + Android SDK 35). El APK queda en `app/build/outputs/apk/release/`.

Para instalarlo en la TV: activa las opciones de desarrollador y la depuración por red, y usa `adb connect <IP-de-la-TV>` + `adb install VisorGoPro-1.0.0.apk` (o cualquier app de «enviar archivos a la TV»).

## Firma

`signing.properties` y `keystore/visorgopro.jks` firman el APK de release y **no se suben al repositorio** (están en `.gitignore`). Mantén siempre la misma llave para poder actualizar sobre la instalación anterior.

- **Local:** deja esos dos archivos en la raíz del proyecto.
- **GitHub Actions:** crea los secretos `KEYSTORE_BASE64` (el `.jks` en base64: `base64 -w0 keystore/visorgopro.jks`) y `KEYSTORE_PASSWORD`. Sin ellos el APK se firma con una llave de debug temporal distinta en cada ejecución, y habría que desinstalar la app antes de instalar la siguiente versión.

## Notas técnicas

- Endpoints usados (Open GoPro): `gopro/camera/info`, `state`, `keep_alive`, `shutter/start|stop`, `setting?setting=&option=`, `presets/get`, `presets/load?id=`, `stream/start?port=8554`, `stream/stop`, `gopro/media/list`, `media/thumbnail`, `media/screennail`, y `videos/DCIM/<carpeta>/<archivo>`.
- Kotlin + Jetpack Compose, Media3 (ExoPlayer) y OkHttp. `minSdk 26`.
