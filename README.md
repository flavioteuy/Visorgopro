# Visor GoPro (Google TV)

App para Google TV / Android TV que se conecta a una GoPro HERO12 Black usando la API abierta de GoPro (Open GoPro, por HTTP) y permite:

- **Galería:** ver los videos y fotos guardados en la tarjeta de la cámara (miniaturas, reproducción por streaming, visor de fotos).
- **Vista en vivo:** vista previa en directo (MPEG-TS por UDP) con botón para grabar / disparar.
- **Configuración:** modo de captura (presets) y ajustes agrupados en Video (resolución, FPS, aspecto, lente, HyperSmooth, rendimiento, tasa de bits, profundidad y perfil de color, HindSight, anti parpadeo), Foto (salida, lente) y Cámara (controles, apagado automático, protector de pantalla, LED, pitidos, GPS), más sincronizar la fecha y hora de la cámara con la TV. Los identificadores salen de la especificación oficial Open GoPro.

## Cómo conectar

**Por Bluetooth (recomendado):** pulsa «Conectar por Bluetooth». La app busca la cámara, la empareja (la primera vez, ponla en modo de emparejamiento: desliza a la izquierda → Conexiones → Conectar dispositivo → GoPro Quik App) y ofrece dos caminos:

- **Usar el WiFi de la cámara:** enciende el WiFi de la cámara, lee su nombre y clave y une la TV a esa red (Android pide confirmación). Si la TV no puede hacerlo sola, la app muestra el nombre y la clave para que la conectes a mano desde los ajustes de la TV. Mientras tanto la TV no tiene internet.
- **Configurar red doméstica (COHN):** escribes el WiFi de tu casa, la cámara se conecta a él y la app crea el acceso seguro (COHN). Se hace una sola vez: después, en el modo «Red doméstica», basta con pulsar Conectar (la IP y las credenciales quedan guardadas) y la TV conserva internet.

**Si tu TV está por cable (Ethernet) y no te deja encender el WiFi:** usa «Conectar por Bluetooth» → «Configurar red doméstica (COHN)». La cámara se une al WiFi de tu router y la TV, por cable, está en ese mismo router, así que no hace falta que la TV tenga WiFi. La app lo detecta y muestra solo esa opción.

**A mano, modo «WiFi de la cámara»:** en la GoPro, desliza a la izquierda → Conexiones → Conectar dispositivo → GoPro Quik App; luego conecta la TV a esa red WiFi desde los ajustes de la TV y abre la app. (Si tu TV tiene Ethernet, la app fuerza su tráfico por el WiFi de la cámara.)

**A mano, modo «Red doméstica (COHN)»:** escribe la IP, el usuario y la contraseña de COHN de una cámara ya configurada.

### Si da error de conexión Bluetooth (código 133)

El 133 es un fallo genérico de Android. La app ya reintenta sola 3 veces (cerrando el intento anterior, esperando y, antes del último, olvidando un emparejamiento antiguo). Si aun así falla:

1. Pon la cámara en modo de emparejamiento (desliza a la izquierda → Conexiones → Conectar dispositivo → GoPro Quik App) y no la tengas conectada a la app Quik del teléfono (la cámara solo acepta una conexión Bluetooth).
2. Si «GoPro …» aparece en los ajustes Bluetooth de la TV (Ajustes → Control remoto y accesorios), elimínala de ahí.
3. Si la cámara se restableció, en la GoPro: Preferencias → Conexiones → Restablecer conexiones.
4. Vuelve a buscar y elige la cámara.

## Control remoto

- Flechas / OK: navegar y elegir. Atrás: volver.
- En un video: **OK** pausa/reanuda, **◀ ▶** saltan 10 s (mantén pulsado para saltar más), **▲** muestra los controles y **▼** (o **Menú**, si tu control lo tiene) alterna entre el original y la versión ligera (LRV). Si la TV no puede decodificar el original (por ejemplo 5.3K), la app cambia sola a la versión ligera.
- En una foto: ◀ ▶ para pasar de foto.

## Compilar el APK

**En la nube (sin instalar nada):** la acción *Compilar APK* se ejecuta sola en cada push a `main`, deja `VisorGoPro-<versión>.apk` en la carpeta `releases/` del repositorio y también como artefacto de la ejecución. Si falla, el resumen del error queda en `releases/build-error.log`.

**En tu computadora:** abre la carpeta con Android Studio, o ejecuta `./gradlew assembleRelease` (JDK 17 + Android SDK 35). El APK queda en `app/build/outputs/apk/release/`.

Para instalarlo en la TV: activa las opciones de desarrollador y la depuración por red, y usa `adb connect <IP-de-la-TV>` + `adb install VisorGoPro-1.2.1.apk` (o cualquier app de «enviar archivos a la TV»).

## Firma (para poder actualizar sin desinstalar)

Android solo instala una versión nueva encima de otra si ambas están firmadas con **la misma llave**. Por eso la llave de firma nunca se sube al repositorio (es público): `signing.properties` y `keystore/` están en `.gitignore`.

- **GitHub Actions (lo habitual):** guarda la llave una sola vez como secretos del repositorio (*Settings → Secrets and variables → Actions*): `KEYSTORE_BASE64` (el `.jks` en base64: `base64 -w0 visorgopro.jks`) y `KEYSTORE_PASSWORD` (sirve para el almacén y para la llave; el alias es `visorgopro`). Con ellos todas las compilaciones salen con la misma firma y se instalan encima de la anterior.
- **Sin secretos:** el APK se firma con una llave de debug temporal distinta en cada ejecución, y hay que desinstalar la app antes de instalar la siguiente versión.
- **Cómo comprobarlo:** cada compilación guarda `releases/firma.txt` con la versión, el tipo de llave usada y la huella SHA-256 del certificado (es un dato público). Si la huella no cambia entre versiones, las actualizaciones se instalan encima.
- **Local:** deja `signing.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) y `keystore/visorgopro.jks` en la raíz del proyecto.

Guarda una copia del `.jks` y de su clave en un lugar privado: si se pierde, habrá que desinstalar la app una vez para firmar con una llave nueva.

## Vista en vivo: diagnóstico

Mientras no hay imagen, la pantalla muestra una línea de diagnóstico: KB recibidos por UDP, estado del reproductor, formato de video y el error si lo hay. Si los KB se quedan en 0, la cámara no está enviando datos a la TV; si suben pero no hay imagen, el problema es el formato/decodificación. La app reintenta sola (detiene y vuelve a pedir la transmisión) probando en cada intento una variante distinta del lector de video (sin audio, sin detección de cuadros, con audio). La línea de diagnóstico también indica qué trae el flujo (por ejemplo «TS: H.264 + AAC»).

## Notas técnicas

- La clave del WiFi de casa se guarda cifrada con AES-GCM; la llave de cifrado vive en el Android Keystore de la TV (`SecretStore.kt`). Se puede ver, dejar de recordar u olvidar desde el formulario de red doméstica.
- La tipografía es Poppins (licencia SIL OFL, en `licenses/Poppins-OFL.txt`).

- Endpoints usados (Open GoPro): `gopro/camera/info`, `state`, `keep_alive`, `shutter/start|stop`, `setting?setting=&option=`, `presets/get`, `presets/load?id=`, `stream/start?port=8554`, `stream/stop`, `gopro/media/list`, `media/thumbnail`, `media/screennail`, y `videos/DCIM/<carpeta>/<archivo>`.
- Bluetooth LE (Open GoPro): servicio `0xFEA6`; comandos en `b5f90072/73`, consultas en `b5f90076/77` y gestión de red en `b5f90091/92`; el emparejamiento se dispara leyendo la clave del WiFi (`b5f90003`). Mensajes protobuf mínimos escritos a mano (sin librería) para `RequestConnectNew`, `RequestGetCOHNStatus`, `RequestCreateCOHNCert` y `RequestClearCOHNCert`.
- Con COHN la app confía en el certificado autofirmado de la cámara sin validarlo, y solo para la IP configurada (red doméstica).
- Kotlin + Jetpack Compose, Media3 (ExoPlayer) y OkHttp. `minSdk 26`.
