# MotoIntercom — PROYECTO

## 0. Estado

- 13/10/2026: Bloque 1 (base técnica) programado, con volumen maestro, controles de música y pantalla de ajustes.
- 14/10/2026: Bloque 2 (app de grupo usable) programado.
  - Compila; las 35 pruebas del núcleo pasan y lint no da avisos propios.
  - Visto en dos emuladores con Bluetooth virtual:
    - Se encuentran por el código de grupo, conectan por L2CAP y completan el saludo cifrado.
    - Los nombres viajan cifrados y la voz pasa (16 kbps, 0 perdidas).
    - La búsqueda pasa a ahorro con la malla estable.
    - Al apagar y encender el Bluetooth, el enlace vuelve solo.
    - El botón de reproducir/pausa (vía MediaSession) silencia el micro y la notificación lo refleja.
    - La invitación por enlace y el asistente del casco funcionan.
- 14/10/2026: Bloque 3 (robustez y calidad) programado: protocolo 3, 40 pruebas del núcleo y lint limpio.
  - Rutas por coste de enlace.
  - Latencia medida y retardo boca-oreja estimado.
  - Lista de cascos con recomendación.
  - Límite de enlaces del chip aprendido.
  - Enlaces muertos por ping.
  - Calidad de voz adaptativa.
  - AAudio (T21) y DRED (T24) descartadas, con su motivo en §7.
  - Visto en dos emuladores:
    - Coste 4 por enlace.
    - Latencia de entrada 3–10 ms y de salida 155–173 ms.
    - Boca-oreja estimado de 270–350 ms, con el búfer bajando solo de 60 a 40 ms.
    - Con la app de un móvil congelada, el otro corta el enlace a los 6 s y reconecta en unos 10 s al volver.
- 14/10/2026: Bloque 3B (uso diario) programado: autoconexión al encender el casco, grupos guardados con uno por defecto y micro siempre del casco. Compila, 40 pruebas y lint limpio.
  - Visto en el emulador: elegir casco (pide permiso; sin emparejados, lista vacía) y la entrada de autoconexión (pide permisos, conecta con el grupo por defecto y avisa de que no hay micro del casco).
  - Sin casco real el emulador no puede mostrar la conexión del casco (ACL) ni el diálogo de asociación de Android.
- 14/10/2026: voz primero (T53–T56). Con un casco clásico no hay micro y música buena a la vez (A2DP y HFP no pueden estar activos juntos), y Eduardo prefiere la voz: el modo por defecto es Voz primero (HFP, micro siempre abierto) y Música primero (A2DP) queda como alternativa, útil con cascos LE Audio. Fuera el PTT: hablar es Abierto, Al hablar (VAD) o Solo escuchar. Voz a 48 kHz, 24 kbps por defecto y escalones hasta 48. Compila, 41 pruebas y lint limpio.
  - Sin casco en el emulador no hay micro en ningún modo: la voz de punta a punta a 48 kHz y el micro LE Audio quedan para probar con los cascos.

## 1. Qué es

Intercomunicador de moto entre móviles por Bluetooth, sin cobertura ni servidores y con cualquier casco. Cada uno lleva su casco emparejado con su móvil, como siempre, y los móviles hablan entre sí. Es un "Discord ligero" por BT con reconexión automática.

- Alcance realista por enlace: 30–100 m (móvil en bolsillo o soporte). La malla con relevo encadena saltos para grupos que se estiran en fila.
- No compite en alcance con el mesh de Sena/Cardo (kilómetros). Lo que resuelve es mezclar marcas (Sena SRL3 con Cardo) y que no haga falta cobertura.

## 2. Decisiones

| Tema | Decisión | Por qué |
|---|---|---|
| Plataforma | Android ahora, iOS después | — |
| Stack | Kotlin nativo + Compose; núcleo `:core` en Kotlin puro (solo stdlib, nada de `synchronized` ni `java.*`) | Lo crítico (L2CAP, audio en tiempo real con ruta HFP/A2DP, segundo plano) es nativo en las dos plataformas: Flutter solo compartiría la interfaz. `:core` pasa a Kotlin Multiplatform sin reescribirse |
| Códec | Opus 1.5.2 (C, `third_party/opus`), 48 kHz mono (banda completa), 20 ms, 24 kbps por defecto, FEC | Compila igual en iOS; PLC y FEC para tramas descartadas. 48 kHz es la frecuencia de A2DP y del mezclador de Android: sin remuestreo |
| Transporte | BLE L2CAP CoC (Android 10+, iOS 11+), sin emparejar en el sistema | Compatible con iOS; detrás de `LinkTransport` para añadir Wi-Fi Aware o internet |
| Topología | Malla con relevo (estado de enlaces + árbol de caminos de menor coste por origen) | En fila, el primero oye al último a través de los del medio; si todos se ven, nadie reenvía; un enlace directo malo se evita si hay dos saltos buenos |
| Calidad de voz | Opus adaptativo por escalones (8, 12, 16, 24, 32, 40, 48 kbps): baja uno si las colas descartan voz, sube cuando van limpias. Por defecto 24: la radio la comparte el BLE con el A2DP de la música, y lo que cae antes es la voz, no la música | Las pérdidas aquí son de saturación (L2CAP no pierde), no de radio: menos bitrate alivia; más redundancia (DRED) cargaría más |
| Grupos | Nombre + clave aleatoria de 32 bytes; se comparte por QR, por enlace `motointercom://join` o pegando el texto | Dos grupos con el mismo nombre no se mezclan; el código corto (del advId) deja comprobar que todos están en el mismo |
| Cifrado | AES-256-GCM por enlace (clave derivada de la del grupo y de dos nonces); el enlace solo vale si el otro demuestra tener la clave | Sin la clave no se oye ni se inyecta nada; el contador implícito solo suma la etiqueta (16 B por trama) |
| Segundo plano | Servicio en primer plano (micrófono + dispositivo conectado) con notificación; el motor vive en la Application | Pantalla apagada y app cerrada sin cortar; la notificación silencia y desconecta |
| Audio | **Voz primero (HFP), el modo por defecto:** micro y voces por el casco como una llamada manos libres, siempre abiertos; la voz va lo mejor que da el casco y la música se oye a calidad de llamada. Vale con cualquier casco. **Música primero (A2DP):** la música no baja nunca de calidad; las voces llegan como multimedia mezcladas con ella; el micro del casco solo con LE Audio, y con un casco clásico solo se escucha. Sin ducking. Solo el casco y el móvil: nunca el micro del móvil ni micros externos | Eduardo (14/10/2026): con un casco clásico no se puede tener música y voz buenas a la vez (A2DP y HFP no coexisten), y prefiere la voz: un canal comunitario donde la voz va bien y la música es secundaria. Cualquier modo sigue siendo Bluetooth puro, sin red |
| Autoconexión | Se elige el casco en Ajustes; al conectarse al móvil el servicio conecta con el grupo por defecto sin abrir la app (solo notificación con Desconectar y Abrir), y cuelga y cierra la app 10 s después de que se vaya | Pedido por Eduardo (14/10/2026): encender el casco y listo |
| Grupos guardados | Lista de grupos; uno por defecto (el de siempre, p. ej. la pareja) y otro activo para salidas en grupo | Pedido por Eduardo (14/10/2026) |
| Volumen | Tres controles en la pantalla principal: maestro, voz y multimedia. El maestro mueve los dos a la par conservando la diferencia; los botones del móvil o del casco mueven el maestro | Pedido por Eduardo (13/10/2026): con voz 4/10 y música 6/10, subir deja 5 y 7 |
| Pantallas | Principal (conectar, hablar, volumen + música, grupo, diagnóstico), Ajustes (identidad, grupo, audio, casco, radio, batería, información) y Probar casco | Pedido por Eduardo (13/10/2026) |
| Hablar | Abierto (por defecto) / Voz (VAD) / Solo escuchar; el botón de reproducir/pausa del casco silencia o devuelve el micro (opcional). Sin PTT | Con guantes y las manos en el manillar no se puede pulsar nada (Eduardo, 14/10/2026) |
| Avisos | Pitidos al abrir/cerrar el micro y cuando alguien entra o sale del grupo (desactivables) | En moto no se mira la pantalla |
| Android | minSdk 29, targetSdk 36, compileSdk 37; `com.sanabria.motointercom` | 29: API de L2CAP |
| Textos | `strings.xml` en inglés + es/fr/de/it/pt; idioma por app desde Android 13 (`locales_config`) | — |

## 3. Arquitectura

```
core/  (Kotlin puro, KMP-ready)
  protocol/  Bytes.kt (big-endian, Seq 16 bits) · Frame.kt (tramas, FrameCodec, FrameAssembler) · AdvInfo.kt (anuncio)
  crypto/    Crypto.kt (CryptoProvider, GroupKeys, LinkCipher) · GroupInvite.kt (Group, invitación motointercom://)
  mesh/      LinkSession.kt (saludo y cifrado de un enlace) · Topology.kt (grafo con costes, Dijkstra por origen, caducidad)
             Router.kt (entrega, reenvío, duplicados por sesión, anuncios) · LinkPolicy.kt (a quién conectar, reintentos)
             LinkQuality.kt (coste de cada enlace con histéresis) · LinkLimitLearner.kt (enlaces que aguanta el chip)
             LinkTransport.kt (interfaz de transporte)
  audio/     Voice.kt (formato, interfaces de códec) · JitterBuffer.kt · Mixer.kt (limitador suave) · Vad.kt · MicGain.kt · TalkSender.kt
             VolumeLink.kt (maestro sobre voz y multimedia) · Earcons.kt (avisos sonoros) · BitrateController.kt (calidad adaptativa)
app/
  MotoIntercomApp.kt (dueña del motor y los ajustes) · IntercomService.kt (servicio en primer plano, notificación, MediaSession, colgar sin casco)
  MainActivity.kt (pantallas, permisos, encender BT, invitaciones: enlace, QR, portapapeles; asociar el casco; autoconexión) · IntercomViewModel.kt
  HelmetReceiver.kt (el casco elegido se conecta o desconecta: abre la app o avisa)
  crypto/    AndroidCrypto.kt (javax.crypto, un Cipher por hilo)
  ble/       BleMesh.kt (anuncio 1M + Coded, escaneo adaptativo, servidor L2CAP, GATT→L2CAP, duplicados, reintentos)
             BleLink.kt (lectura/escritura por enlace; cifra al escribir; cola con descarte de voz vieja) · GattHandle
  audio/     AudioIo.kt (música primero y manos libres, micro del casco (LE Audio o SCO), recuperación de la ruta al casco, teclas multimedia) · VolumeOutputs.kt
             HelmetTester.kt (asistente: grabar, reproducir, medir) + HelmetCompat (resultados por casco, lista, recomendación)
  assets/    helmets.tsv (lista de cascos con modo recomendado; se rellena con los resultados compartidos)
  opus/      Opus.kt + cpp/opus_jni.c (JNI), cpp/CMakeLists.txt (Opus como estática, -O2 también en Debug)
  engine/    IntercomEngine.kt (une todo; malla que se rehace con el BT; avisos; micro del casco) · Settings.kt (+ repositorio, grupos guardados)
  ui/        MainScreen · SettingsScreen · HelmetTestScreen · GroupDialogs (QR, grupo nuevo, unirse) · Components · Theme
```

Hilos:
- Captura: VAD + Opus + envío.
- Reproducción: jitter buffers + decodificación + mezcla + avisos.
- Lectura y escritura por enlace (la escritura cifra).
- `ble-mesh`: política de enlaces, ping, RSSI, escaneo adaptativo, reintentos.
- `engine-tick`: anuncios de malla, caducidad, botones de volumen, avisos de grupo, reintento de malla, estado.

Cerrojos: malla → jitter buffers, siempre en ese orden. `BleMesh` nunca avisa al motor con su cerrojo cogido.

## 4. Protocolo (versión 3)

**Anuncio BLE.** Servicio `6d6f746f-6963-4f6d-a001-000000000001`. Datos de servicio (12 B): `u8 protocolo, u32 grupo (advId), u32 nodo, u16 PSM, u8 flags`.
- Anuncio normal (1M, conectable y escaneable): el UUID va en el anuncio y los datos en la respuesta al escaneo.
- Si el chip soporta Coded PHY y anuncio extendido, hay además un anuncio extendido en Coded PHY con todo en un paquete.

**Claves** (HMAC-SHA256 de la clave de grupo K con etiquetas `motointercom/…`):
- `advId` = 4 primeros bytes de HMAC(K, "adv").
- Clave de saludo = HMAC(K, "hello").
- Clave de cifrado = HMAC(K, "enc"). La clave de cada enlace es HMAC(cifrado, "link" ‖ nonce del que inicia ‖ nonce del que acepta).

**Conexión.** Inicia el de id menor. El mayor espera 8 s por si el otro no le ve (alcance asimétrico) y entonces inicia él.
- `connectGatt` con 1M + Coded y prioridad alta. Sobre esa misma conexión, `createInsecureL2capChannel(PSM)`.
- El que acepta abre también un cliente GATT sobre la conexión existente, para el RSSI y el PHY.
- Si se cruzan dos enlaces, gana siempre el que inició el de id menor, aunque aún no haya terminado de saludar.

**Saludo** (`LinkSession`):
1. Cada extremo envía un Hello en claro: protocolo, nodo, advId, nonce de 16 B y MAC de 16 B con la clave de saludo.
2. Al recibir el del otro se comprueban versión, grupo y MAC, y se deriva la clave del enlace.
3. Desde ahí todo va cifrado, empezando por Ready. El enlace vale al recibir el Ready del otro.

Un Hello copiado por alguien de fuera no pasa del paso 2.

**Tramas.** `[u16 longitud][contenido]`, como mucho 2048. Contenido en claro (solo el Hello) = `[u8 tipo][cuerpo]`. Contenido cifrado = AES-256-GCM de `[u8 tipo][cuerpo]` + etiqueta de 16 B. El nonce de 12 B es sentido (1 o 2) + contador de 64 bits; no viaja, porque L2CAP entrega en orden y lo que se descarta se descarta antes de cifrar. Un tipo desconocido se salta.

| Tipo | Cuerpo |
|---|---|
| 1 Hello | `u8 protocolo, u32 nodo, u32 advId, 16 B nonce, 16 B MAC` (en claro) |
| 6 Ready | — (primera trama cifrada) |
| 2 Audio | `u32 origen, u32 sesión, u32 contador, u8 flags (1 START, 2 END), u8 saltos, paquete Opus` |
| 3 LinkState | `u32 origen, u16 versión, u8 saltos, u8 len + nombre, u16 latencia del micro (ms), u8 n, n × (u32 vecino, u8 coste)` |
| 4 Ping / 5 Pong | `u32 tiempo` (RTT por enlace) |

**Invitación:** `motointercom://join?v=1&n=<nombre en %>&k=<clave base64url>`. Va en el QR, se comparte como texto y abre la app desde un enlace.

## 5. Malla con relevo

- Cada nodo inunda su lista de vecinos (LinkState) cada 2 s y al abrir o cerrar un enlace. Una entrada caduca a los 6 s sin noticias. El nombre de cada nodo viaja aquí (cifrado), no en el Hello.
- Un enlace entre otros dos nodos existe si cualquiera de los dos lo anuncia. Los enlaces propios los decide el estado local.
- **Coste de cada enlace** (`LinkQuality`, cada 2 s, va en el LinkState): 4 por salto más penalizaciones.
  - Señal (RSSI suavizado): +0 desde −70 dBm, +2 desde −80, +4 desde −88 y +6 por debajo.
  - Voz descartada en la cola: +0 por debajo del 1 %, +2 hasta el 5 %, +4 hasta el 15 % y +6 por encima.
  - RTT por encima de 150 ms: +2.
  - Histéresis: el coste anunciado solo cambia si el nuevo se repite 3 veces seguidas.
  - El coste de un enlace es el peor de lo que anuncien sus dos extremos.
- Para cada origen, todos calculan el mismo árbol de menor coste (Dijkstra; a igual distancia gana el padre de id menor). Un nodo solo reenvía a sus hijos en el árbol del origen.
- Si el origen aún no está en el grafo, se inunda a todos menos al que la trajo.
- Duplicados: ventana de contadores por origen y sesión. Una sesión nueva (el emisor se ha reconectado) empieza ventana y cola de reproducción.
- Límite de 8 saltos.
- Enlaces: como mucho `maxLinks` (4 por defecto), los de mejor RSSI. Se aceptan hasta 2 entrantes de más.
- **Límite del chip** (`LinkLimitLearner`): Android no dice cuántos enlaces LE aguanta el controlador.
  - Se aprende: si añadir un enlace falla 3 veces seguidas con los mismos enlaces abiertos (al menos 2) y un nodo que se ve a −75 dBm o mejor, ese número es el límite.
  - Si luego un enlace más funciona (también uno entrante), sube.
  - Se guarda por móvil y se puede olvidar en Ajustes → Radio.
- **Enlaces muertos:** sin nada del otro (ni el pong del ping de cada segundo) en 6 s, se cierra y se reconecta sin esperar al timeout del Bluetooth.
- Reintentos tras un fallo: 1, 2, 4, 8 s y como mucho 15 s.
- **Escaneo adaptativo:** rápido mientras no hay enlaces, falta alguien del grupo que se ve o la malla ha cambiado hace menos de 30 s; si no, en ahorro. Se rearranca como mucho cada 10 s (Android permite 5 arranques cada 30 s). Si el escaneo o el anuncio fallan, se reintentan.
- **Bluetooth apagado:** se cierra la malla y se avisa en pantalla y en la notificación; al encenderlo se crea una nueva. Si falla, se reintenta cada 5 s.

## 6. Audio

- **Captura:** 960 muestras (20 ms a 48 kHz). El VAD va siempre (para el medidor).
  - Detector: el ruido es el mínimo del nivel en 1,6 s, más 3 dB. El umbral va de +6 a +20 dB según la sensibilidad, con un mínimo de −50 dBFS. Abre tras 2 tramas y mantiene 400 ms de cola.
  - Opus codifica todo, aunque no se envíe. `TalkSender` envía 2 tramas previas (pre-roll) al abrir y marca START/END.
- **Micro desde el casco o la notificación:** lo silencia o lo devuelve. Suena un pitido agudo o grave según cómo queda. Silenciado o en Solo escuchar, en música primero se suelta el micro del casco y queda solo la música.
- **Ganancia del micro** (`MicGain`): el micro del casco llega bajo. Mientras el detector da voz, la ganancia lleva el nivel hacia −18 dBFS (sube 6 dB/s, baja 60 dB/s, hasta +18 dB, sin atenuar nunca y con limitador suave). Sin voz no se mueve. El detector mira la señal sin ganancia.
- **Envío:** cola por enlace de 5 tramas de voz (100 ms). Si va llena se tira la más vieja; el control no se descarta.
  - **Contrapresión:** el socket L2CAP no avisa de que está lleno (escribe en una cola enorme de la pila Bluetooth), así que el retardo se acumulaba ahí sin límite. Ahora hay un ping cada 500 ms, uno a la vez, por el mismo canal que la voz. Si el RTT o la edad del ping pendiente pasan de 300 ms, la voz nueva se descarta (cuenta como descarte y baja la calidad adaptativa) hasta que el canal se vacía.
- **Calidad adaptativa** (`BitrateController`, cada 2 s, con la voz enviada y descartada por todos los enlaces):
  - Con más del 5 % descartado baja un escalón (48, 40, 32, 24, 16, 12, 8 kbps), hasta 8 kbps.
  - Tras 5 periodos por debajo del 1 % sube un escalón, hasta el máximo de Ajustes.
  - Con menos de 25 tramas en el periodo no decide.
- **Latencia:** `AudioRecord.getTimestamp` y `AudioTrack.getTimestamp`, una vez por segundo, dan cuánto tarda el micro en entregar y la salida en sonar (incluido el Bluetooth si el sistema lo informa).
  - Cada nodo anuncia la latencia de su micro en el LinkState.
  - El retardo boca-oreja de cada miembro se estima como: micro del otro + trama (20 ms) + red (½ RTT por salto) + cola de reproducción + salida propia.
- **Recepción:** un jitter buffer por origen, que empieza en 2 tramas y llega a 12.
  - Si falta una trama y hay posteriores, se da por perdida: se reconstruye con el FEC de la siguiente o con PLC.
  - Si no llega nada, el objetivo sube una trama y vuelve a acumular sin saltar la que falta. A los 300 ms sin nada, la ráfaga se acaba.
  - Si sobra cola durante 0,2 s, se recorta una trama. Si sobran más de 5 tramas (una ráfaga que el Bluetooth retuvo), se descarta hasta el objetivo de una vez. Antes se recortaba una trama cada 0,5 s: 60 tramas de ráfaga tardaban 30 s en absorberse. Tras 10 s sin cortes, el objetivo baja.
- **Mezcla:** suma de voces y avisos, ganancia de voces (la calcula `VolumeOutputs`) y limitador suave a partir de 0,7.
- **Volumen** (niveles 0..1; 1 nivel = 40 dB). `VolumeLink` guarda maestro M = el mayor de voz V y multimedia U, y la diferencia de cada uno con él. Si uno toca fondo, la diferencia se recupera al volver a subir. Lo que se oye debe ser voz ≈ V y música ≈ U:
  - **Casco (HFP):** todo va por SCO y el casco aplica el volumen de llamada a todo. Llamada = M, multimedia = 1 + U − M, voces = ganancia(V − M).
  - **Mezcla, voces como multimedia:** voces y música comparten el canal. Multimedia = U (como poco un paso si hay voz), voces = ganancia(V − U), como mucho +12 dB.
  - **Mezcla, voces como llamada:** multimedia = U, llamada = V.
  - **Botones:** el canal que manda (llamada en HFP, multimedia en mezcla) se lee cada 250 ms. Lo que suba o baje por botones del móvil o del casco se suma al maestro. El paso lo pone el sistema (en el emulador, la llamada sube de 3 en 3).
- **Música:** anterior, reproducir/pausa y siguiente con `dispatchMediaKeyEvent`; los recibe la app de música activa. Con el botón del casco activado, reproducir/pausa lo recibe la MediaSession de la app y no la música.
- **Rutas:**
  - **Casco (HFP):** `MODE_IN_COMMUNICATION` + `setCommunicationDevice` (LE Audio si lo hay; si no, SCO). Fuente `VOICE_COMMUNICATION` y uso `USAGE_VOICE_COMMUNICATION`. Si el casco se va y vuelve, `AudioDeviceCallback` rehace la ruta.
  - **Música primero:** `MODE_NORMAL`; el casco no sale nunca de A2DP (o de multimedia LE Audio). Las voces salen como multimedia (con `setPreferredDevice` al casco) o como llamada. Micro: solo LE Audio (`TYPE_BLE_HEADSET` como dispositivo de comunicación). Con un casco clásico no se abre micro y la pantalla lo explica. En Diagnóstico se ve por qué dispositivo sale cada cosa.
  - **Micro:** siempre el del casco (`TYPE_BLE_HEADSET`, o `TYPE_BLUETOOTH_SCO` en manos libres, con `setPreferredDevice`). Si no hay, no se abre y la pantalla lo avisa; nunca se usa el del móvil.
- La reproducción escribe silencio cuando nadie habla, para que A2DP/SCO no se duerma.
- **Asistente del casco:**
  1. Prepara la ruta de llamada y espera hasta 6 s a que el micro venga del casco.
  2. Graba 3 s por el micro del casco y los reproduce: en HFP por la llamada, en mezcla por A2DP.
  3. Pregunta si se ha oído bien.

  Guarda por casco si cada modo funcionó y el tiempo de ruta, y recomienda Música primero si funciona; si no, Manos libres.
- **Lista de cascos:** `assets/helmets.tsv` (nombre o parte del nombre Bluetooth, modo, nota).
  - Si hay un casco conectado con modo recomendado distinto del puesto, la pantalla principal lo propone, por la prueba de este móvil o, si no se ha probado, por la lista.
  - «Compartir resultados» exporta las pruebas en TSV para ir llenando la lista.

## 7. Hoja de ruta

### Bloque 1 — Base técnica

- [x] T01 Proyecto (Gradle 9.8, AGP 9.4.1, Kotlin 2.4.21, Compose) y Opus 1.5.2 por NDK con puente JNI
- [x] T02 Núcleo: protocolo, malla con relevo, política de enlaces, jitter buffer, mezcla, VAD y ráfagas, con pruebas
- [x] T03 Transporte BLE: anuncio 1M + Coded, escaneo, L2CAP, saludo, duplicados, ping, RSSI y PHY
- [x] T04 Audio: modos casco (HFP) y mezcla (A2DP), dos volúmenes, ruta visible
- [x] T05 Interfaz: conexión, hablar (modos, PTT, medidor), grupo (saltos, búfer, pérdidas) y diagnóstico (ruta de audio; enlaces con RSSI, PHY, RTT, kbps, cola y descartes)
- [x] T06 Volumen en la pantalla principal: maestro, voz y multimedia. El maestro mueve los dos conservando la diferencia, y los botones del móvil o del casco mueven el maestro
- [x] T07 Controles de música en la pantalla principal: anterior, reproducir/pausa y siguiente
- [x] T08 Pantalla de ajustes aparte
- [-] T09 "Atrás" sin colgar — absorbida por T10: el motor vive en la Application y lo mantiene el servicio, cerrar la actividad ya no corta

### Bloque 2 — App de grupo usable

- [x] T10 Servicio en primer plano (`microphone|connectedDevice`) con notificación (miembros, estado del micro, silenciar/hablar, desconectar) y wake lock; fuera la pantalla siempre encendida
- [x] T11 Reconexión: la malla se rehace al apagar y encender el Bluetooth, el escaneo y el anuncio se reintentan, y la ruta al casco se recupera cuando vuelve
- [x] T12 Grupos con clave: crear, mostrar QR, compartir, escanear QR (escáner de Google Play Services, sin permiso de cámara), pegar invitación y enlace `motointercom://join`, con confirmación y código corto
- [x] T13 Cifrado por enlace (AES-256-GCM, clave por enlace, saludo autenticado y Ready), con pruebas de grupo ajeno, Hello copiado y trama manipulada
- [x] T14 Botón de reproducir/pausa del casco (MediaSession): abre o cierra el micro, con pitido; avisos sonoros de entrada y salida del grupo
- [x] T15 Asistente de compatibilidad del casco: grabar, reproducir, medir la ruta, recomendar modo y guardar por casco
- [x] T16 Escaneo adaptativo: rápido mientras falta alguien o la malla cambia, en ahorro con los enlaces estables
- [x] T17 Batería: aviso si Android puede cortar la app y acceso directo a la optimización de batería

### Bloque 3 — Robustez y calidad

- [x] T20 Rutas por calidad de enlace: coste por RSSI, descartes y RTT con histéresis, anunciado en el LinkState (protocolo 3) y Dijkstra determinista
- [-] T21 AAudio — descartada. Con el casco por Bluetooth (SCO/A2DP) la latencia la ponen el enlace y su búfer, no la API; el camino MMAP de AAudio solo sirve para altavoz y cable. Sustituida por T25, que mide la latencia real
- [x] T22 Lista de cascos: `assets/helmets.tsv` + resultados del asistente, recomendación al conectar un casco y exportación de resultados
- [x] T23 Límite de enlaces del chip, aprendido por fallos con buena señal, guardado por móvil y con opción de olvidarlo
- [-] T24 Opus DRED — descartada. L2CAP no pierde: las pérdidas solo salen de colas saturadas, donde más redundancia añade carga; además exige varios MB de pesos de red neuronal. Sustituida por T27
- [x] T25 Latencia medida (entrada y salida del móvil) en Diagnóstico y retardo boca-oreja estimado por miembro del grupo
- [x] T26 Enlaces muertos: sin respuesta en 6 s se cierran y se reconectan
- [x] T27 Calidad de voz adaptativa (16 → 12 → 8 kbps según los descartes); el ajuste pasa a ser el máximo

### Bloque 3B — Uso diario

- [x] T50 Autoconexión: elegir el casco entre los emparejados y asociarlo como dispositivo complementario (deja arrancar el micrófono desde segundo plano); receptor de conexión y desconexión del casco; el servicio arranca solo al conectarse el casco, sin abrir la app (solo la notificación, con Desconectar y Abrir; permisos REQUEST_COMPANION_*); notificación de reserva para conectar con un toque si no se puede; colgar y cerrar la app 10 s después de que el casco se vaya
- [x] T51 Grupos guardados: lista con activo y por defecto (usar, hacer por defecto, borrar); selector en la pantalla principal; la autoconexión usa el grupo por defecto
- [x] T52 Micro siempre del casco: fuera la elección de micro; aviso si no hay micro del casco. (Lo de hablar con PTT en mezcla lo sustituyen T53 y T54.)
- [x] T53 Música primero: A2DP sin salir de él; micro del casco solo con LE Audio; manos libres (HFP) como alternativa (el modo por defecto lo cambia T56); la prueba del casco recomienda música primero; los ajustes antiguos (manos libres, PTT, 16 kbps) se pasan a lo nuevo una vez
- [x] T54 Fuera el PTT: Abierto (por defecto), Voz (VAD) y Solo escuchar; el botón del casco y la notificación silencian o devuelven el micro, y silenciado se suelta el micro del casco
- [x] T55 Voz a 48 kHz (banda completa, sin remuestreo), complejidad 10, escalones de calidad hasta 48 kbps y 24 kbps por defecto
- [x] T56 Voz primero por defecto: Voz primero (HFP) pasa a ser el modo normal y Música primero (A2DP) la alternativa; la prueba del casco recomienda Voz primero si funciona; los ajustes de audio se reinician una vez a lo nuevo. En HFP la voz va por SCO (16 kHz con mSBC si el casco y el móvil lo negocian; 8 kHz si no): no se puede forzar desde la app

### Bloque 4 — iOS (necesita un Mac con Xcode)

- [ ] T30 `:core` → Kotlin Multiplatform
- [ ] T31 CoreBluetooth L2CAP. El PSM también por característica GATT: en segundo plano iOS no anuncia datos de servicio
- [ ] T32 AVAudioSession (`playAndRecord` + `allowBluetooth` / `allowBluetoothA2DP`)
- [ ] T33 Interfaz (Compose Multiplatform o SwiftUI)
- [ ] T34 CryptoProvider con CryptoKit
- [ ] T35 Distribución sin App Store y sin Mac propio. Plan:
  - Compilar en un Mac en la nube (GitHub Actions con `macos-latest`, Codemagic o Xcode Cloud): no hace falta tener un Mac, pero sí escribir el código sin verlo funcionar en un iPhone.
  - Apple Developer Program: 99 USD al año, imprescindible para TestFlight.
  - TestFlight: hasta 10 000 probadores con un enlace público; cada build caduca a los 90 días (hay que resubir) y la primera build de cada versión pasa una revisión corta de Apple (no es la del App Store).
  - Alternativas descartadas: ad hoc (hay que dar el UDID de cada iPhone y vale para 100 aparatos), sideloading con ID gratuito (caduca a los 7 días), tiendas alternativas o distribución web de la UE (solo en la UE, con notarización de Apple y comisiones).
  - Los dispositivos de Eduardo no valen: el iPhone 5 se quedó en iOS 10 (L2CAP exige iOS 11) y el iPad Air 2 llega a iPadOS 15. Hace falta un iPhone de un amigo que pruebe las builds.
  - Riesgos del lado de iOS: el anuncio BLE en segundo plano pierde los datos de servicio, y el micro del casco por HFP en `AVAudioSession` se negocia distinto al de Android.

### Bloque 5 — Opcional

- [ ] T40 Transporte por internet cuando hay cobertura, con salto automático a BT

## 8. Aprendizajes

- **Opus desde git:**
  - Su CMake lee `configure.ac`, `Makefile.am` y los `*.mk`, así que hay que copiarlos.
  - Sin el fichero `package_version` no sabe la versión.
  - Sin DRED/OSCE no hacen falta los pesos de `dnn/` (y `dnn/torch` sobra).
  - En Debug compila sin optimizar y es muy lento: `-O2` forzado.
- **`connectGatt`:** las sobrecargas están obsoletas en API 37. Se siguen usando, porque la sustituta no existe en 29–36.
- **"Atrás" en la actividad raíz:** en el emulador (Android 16) cierra la tarea entera. Por eso el motor no puede vivir en el ViewModel.
- **Botones de volumen:** cada pulsación sube el número de pasos que decida el sistema, no siempre uno. Por eso el maestro se mueve con la diferencia real de índice.
- **Escáner de QR de Play Services:** trae un `androidx.fragment` viejo y lint falla con `registerForActivityResult`. Se fuerza `fragment-ktx` 1.8.5.
- **Núcleo KMP:** `synchronized` y `toSortedSet` son solo JVM; en `:core` se evitan y los cerrojos los pone quien lo usa. `kotlin.concurrent.Volatile` sí es común.
- **Emuladores:** dos AVD arrancados a la vez comparten Bluetooth virtual (anuncio, escaneo, Coded PHY, L2CAP): sirven para probar la malla de punta a punta. Sin casco, el asistente lo detecta ("la ruta no llegó al casco").
- **Rutas con costes:** para que todos los nodos calculen el mismo árbol, el coste de un enlace propio no puede ser la medida del momento. Es el último coste anunciado (el que ven los demás), combinado igual que el resto: el peor de los dos extremos.
- **Abrir la app desde segundo plano:** Android 10+ lo bloquea, salvo para apps con un dispositivo complementario asociado (`CompanionDeviceManager`). Por eso se asocia el casco al elegirlo; si Android no lo deja, queda la notificación.
- **Micro del casco con la música:** un casco Bluetooth clásico solo tiene micro en HFP (llamada), y entonces todo, música incluida, va a calidad de llamada. Por eso Discord hunde la música, y la app no puede evitarlo. Detectar la voz para cambiar de modo tampoco sirve, porque para detectarla el micro ya tiene que estar abierto. LE Audio (LC3) sí da micro y buena calidad a la vez, pero solo si el casco lo tiene.
- **Riesgo abierto:** el BLE entre móviles y el audio Bluetooth al casco comparten la radio del teléfono. Con SCO o A2DP activos, el enlace BLE puede ir más justo. Se mira en "Enlaces": kbps, cola y descartadas.
- **R8 en release (0.2.0):** `isMinifyEnabled` y `isShrinkResources` activos (Play avisaba de ofuscación 1 %). `proguard-rules.pro` conserva los métodos nativos de `OpusNative` (el JNI los busca por nombre). En la prueba el APK de release pasó de 27 MB (el anterior) a 3,9 MB. Al subir la versión hay que probar la release (voz, QR, notificación), no solo la debug.
- **Avisos de Play Console:** (1) la orientación vertical la fijaba la actividad del escáner de ML Kit; se quita en el manifiesto (`tools:replace`). (2) De borde a borde: `enableEdgeToEdge()` arrastra `setStatusBarColor`/`setNavigationBarColor` (obsoletos); se sustituye por barras transparentes en el tema y `WindowCompat` en `MotoTheme` (iconos claros u oscuros según el modo). Comprobado que ya no están en el dex de release.
