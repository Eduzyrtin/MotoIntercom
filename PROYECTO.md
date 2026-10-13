# MotoIntercom — PROYECTO

## 0. Estado

- 13/10/2026: Bloque 1 (base técnica) programado, con volumen maestro, controles de música y pantalla de ajustes (T06–T09). Compila (Debug, arm64-v8a + x86_64), las 27 pruebas del núcleo pasan y lint no da avisos propios. En el emulador arranca, anuncia (1M y Coded), escanea, abre el canal L2CAP y capta el micro.

## 1. Qué es

Intercomunicador de moto entre móviles por Bluetooth, sin cobertura ni servidores y con cualquier casco. Cada uno lleva su casco emparejado con su móvil, como siempre, y los móviles hablan entre sí. Es un "Discord ligero" por BT con reconexión automática.

- Alcance realista por enlace: 30–100 m (móvil en bolsillo o soporte). La malla con relevo encadena saltos para grupos que se estiran en fila.
- No compite en alcance con el mesh de Sena/Cardo (kilómetros). Lo que resuelve es mezclar marcas (Sena SRL3 con Cardo) y que no haga falta cobertura.

## 2. Decisiones

| Tema | Decisión | Por qué |
|---|---|---|
| Plataforma | Android ahora, iOS después | — |
| Stack | Kotlin nativo + Compose; núcleo `:core` en Kotlin puro (solo stdlib) | Lo crítico (L2CAP, audio en tiempo real con ruta HFP/A2DP, segundo plano) es nativo en las dos plataformas: Flutter solo compartiría la interfaz. `:core` pasa a Kotlin Multiplatform sin reescribirse |
| Códec | Opus 1.5.2 (C, `third_party/opus`), 16 kHz mono, 20 ms, 16 kbps, FEC | Compila igual en iOS; PLC y FEC para tramas descartadas |
| Transporte | BLE L2CAP CoC (Android 10+, iOS 11+), sin emparejar en el sistema | Compatible con iOS; detrás de `LinkTransport` para añadir Wi-Fi Aware o internet |
| Topología | Malla con relevo (estado de enlaces + árbol de caminos mínimos por origen) | En fila, el primero oye al último a través de los del medio; si todos se ven, nadie reenvía |
| Audio | Modo casco (HFP) y modo mezcla (A2DP + micro del móvil), sin ducking | — |
| Volumen | Tres controles en la pantalla principal: maestro, voz y multimedia. El maestro mueve los dos a la par conservando la diferencia; los botones del móvil o del casco mueven el maestro | Pedido por Eduardo (13/10/2026): con voz 4/10 y música 6/10, subir deja 5 y 7 |
| Pantallas | Principal (conectar, hablar, volumen + música, grupo, diagnóstico) y Ajustes aparte (identidad, audio, radio, información) | Pedido por Eduardo (13/10/2026) |
| Hablar | Auto (VAD) / PTT en pantalla / abierto / silencio | Viento: el VAD da falsos positivos, por eso el PTT |
| Identidad | Id de nodo aleatorio de 32 bits por móvil; grupo = nombre (FNV-1a) | El QR con clave llega en el bloque 2 |
| Android | minSdk 29, targetSdk 36, compileSdk 37; `com.sanabria.motointercom` | 29: API de L2CAP |
| Textos | `strings.xml` en inglés + es/fr/de/it/pt; idioma por app desde Android 13 (`locales_config`) | — |

## 3. Arquitectura

```
core/  (Kotlin puro, KMP-ready)
  protocol/  Bytes.kt (big-endian, Seq 16 bits) · Frame.kt (tramas, FrameCodec, FrameAssembler) · AdvInfo.kt (anuncio, GroupId)
  mesh/      Topology.kt (grafo, árboles BFS por origen, caducidad) · Router.kt (entrega, reenvío, duplicados, anuncios)
             LinkPolicy.kt (a quién conectar, reintentos) · LinkTransport.kt (interfaz de transporte)
  audio/     Voice.kt (formato, interfaces de códec) · JitterBuffer.kt · Mixer.kt (limitador suave) · Vad.kt · TalkSender.kt
             VolumeLink.kt (maestro sobre voz y multimedia, con la diferencia guardada)
app/
  ble/       BleMesh.kt (anuncio 1M + Coded, escaneo, servidor L2CAP, conexión GATT→L2CAP, saludo, duplicados)
             BleLink.kt (lectura/escritura por enlace, cola con descarte de voz vieja, ping) · GattHandle (RSSI, PHY, prioridad)
  audio/     AudioIo.kt (rutas HFP/mezcla, AudioRecord/AudioTrack en hilos de prioridad de audio, teclas multimedia)
             VolumeOutputs.kt (niveles → volúmenes del sistema y ganancia de voces según el modo; botones → maestro)
  opus/      Opus.kt + cpp/opus_jni.c (JNI), cpp/CMakeLists.txt (Opus como estática, -O2 también en Debug)
  engine/    IntercomEngine.kt (une todo; estado para la interfaz) · Settings.kt
  ui/        MainScreen.kt · SettingsScreen.kt · Components.kt · Theme.kt
  MainActivity.kt (permisos, encender BT, pantallas, pantalla siempre encendida y "atrás" sin colgar mientras conecta)
  IntercomViewModel.kt (guarda también los volúmenes que mueven los botones)
```

Hilos: captura (VAD + Opus + envío), reproducción (jitter buffers + decodificación + mezcla), lectura y escritura por enlace, `ble-mesh` (política de enlaces, ping, RSSI) y `engine-tick` (anuncios de malla, caducidad, estado). Cerrojos: malla → jitter buffers, siempre en ese orden. `BleMesh` nunca avisa al motor con su cerrojo cogido.

## 4. Protocolo (versión 1)

**Anuncio BLE.** Servicio `6d6f746f-6963-4f6d-a001-000000000001`. Datos de servicio (12 B): `u8 protocolo, u32 grupo, u32 nodo, u16 PSM, u8 flags`.
- Anuncio normal (1M, conectable y escaneable): el UUID va en el anuncio y los datos en la respuesta al escaneo.
- Si el chip soporta Coded PHY y anuncio extendido, hay además un anuncio extendido en Coded PHY con todo en un paquete.

**Conexión.** Inicia el de id menor. El mayor espera 8 s por si el otro no le ve (alcance asimétrico) y entonces inicia él.
- `connectGatt` con 1M + Coded y prioridad alta. Sobre esa misma conexión, `createInsecureL2capChannel(PSM)`.
- El que acepta abre también un cliente GATT sobre la conexión existente, para el RSSI y el PHY.
- Si se cruzan dos enlaces, ambos lados se quedan con el que inició el de id menor.

**Tramas.** `[u16 longitud][u8 tipo][cuerpo]`, big-endian. Longitud = 1 + cuerpo, como mucho 2048. Un tipo desconocido se salta.

| Tipo | Cuerpo |
|---|---|
| 1 Hello | `u8 protocolo, u32 nodo, u32 grupo, u8 len + nombre UTF-8 (≤32 B)` — primera trama en cada sentido; grupo o versión distintos = se corta |
| 2 Audio | `u32 origen, u16 seq, u8 flags (1 START, 2 END), u8 saltos, paquete Opus` |
| 3 LinkState | `u32 origen, u16 versión, u8 saltos, u8 len + nombre, u8 n, n × u32 vecino` |
| 4 Ping / 5 Pong | `u32 tiempo` (RTT por enlace) |

## 5. Malla con relevo

- Cada nodo inunda su lista de vecinos (LinkState) cada 2 s y al abrir o cerrar un enlace. Una entrada caduca a los 6 s sin noticias.
- Un enlace entre otros dos nodos existe si cualquiera de los dos lo anuncia. Los enlaces propios los decide el estado local.
- Para cada origen, todos calculan el mismo árbol BFS (desempate por id). Un nodo solo reenvía a sus hijos en el árbol del origen.
- Si el origen aún no está en el grafo, se inunda a todos menos al que la trajo.
- Duplicados: ventana por origen de (seq, instante). La misma seq pasados 2 s cuenta como nueva, por si el emisor se reinicia.
- Límite de 8 saltos.
- Enlaces: como mucho `maxLinks` (4 por defecto), los de mejor RSSI. Se aceptan hasta 2 entrantes de más.
- Reintentos tras un fallo: 1, 2, 4, 8 s y como mucho 15 s.

## 6. Audio

- **Captura:** 320 muestras (20 ms). El VAD va siempre (para el medidor).
  - Detector: el ruido es el mínimo del nivel en 1,6 s, más 3 dB. El umbral va de +6 a +20 dB según la sensibilidad, con un mínimo de −50 dBFS. Abre tras 2 tramas y mantiene 400 ms de cola.
  - Opus codifica todo, aunque no se envíe. `TalkSender` envía 2 tramas previas (pre-roll) al abrir y marca START/END.
- **Envío:** cola por enlace de 5 tramas de voz (100 ms). Si va llena se tira la más vieja; el control no se descarta.
- **Recepción:** un jitter buffer por origen, que empieza en 2 tramas y llega a 12.
  - Si falta una trama y hay posteriores, se da por perdida: se reconstruye con el FEC de la siguiente o con PLC.
  - Si no llega nada, el objetivo sube una trama y vuelve a acumular sin saltar la que falta. A los 300 ms sin nada, la ráfaga se acaba.
  - Si sobra cola durante 0,5 s, se recorta una trama. Tras 10 s sin cortes, el objetivo baja.
- **Mezcla:** suma, ganancia de voces (la calcula `VolumeOutputs`) y limitador suave a partir de 0,7.
- **Volumen** (niveles 0..1; 1 nivel = 40 dB). `VolumeLink` guarda maestro M = el mayor de voz V y multimedia U, y la diferencia de cada uno con él. Si uno toca fondo, la diferencia se recupera al volver a subir. Lo que se oye debe ser voz ≈ V y música ≈ U:
  - **Casco (HFP):** todo va por SCO y el casco aplica el volumen de llamada a todo. Llamada = M, multimedia = 1 + U − M, voces = ganancia(V − M).
  - **Mezcla, voces como multimedia:** voces y música comparten el canal. Multimedia = U (como poco un paso si hay voz), voces = ganancia(V − U), como mucho +12 dB.
  - **Mezcla, voces como llamada:** multimedia = U, llamada = V.
  - **Botones:** el canal que manda (llamada en HFP, multimedia en mezcla) se lee cada 250 ms. Lo que suba o baje por botones del móvil o del casco (volumen absoluto AVRCP / HFP) se suma al maestro. El paso lo pone el sistema (en el emulador, la llamada sube de 3 en 3).
  - Si el casco cambia su volumen por dentro sin decírselo al móvil, ya hace de maestro físico: sube todo a la vez.
- **Música:** anterior, reproducir/pausa y siguiente con `dispatchMediaKeyEvent`; los recibe la app de música activa.
- **Rutas:**
  - **Casco (HFP):** `MODE_IN_COMMUNICATION` + `setCommunicationDevice` (LE Audio si lo hay; si no, SCO). Fuente `VOICE_COMMUNICATION` y uso `USAGE_VOICE_COMMUNICATION`.
  - **Mezcla:** `MODE_NORMAL`, micro con cable o USB si hay, si no el del móvil. Fuente procesada (`VOICE_COMMUNICATION`) o directa (`MIC`).
    - Las voces salen como multimedia (A2DP, siguen al volumen de la música) o como llamada (volumen propio).
    - En Avanzado se ve por qué dispositivo sale cada cosa.
- La reproducción escribe silencio cuando nadie habla, para que A2DP/SCO no se duerma.

## 7. Hoja de ruta

### Bloque 1 — Base técnica

- [x] T01 Proyecto (Gradle 9.8, AGP 9.4.1, Kotlin 2.4.21, Compose) y Opus 1.5.2 por NDK con puente JNI
- [x] T02 Núcleo: protocolo, malla con relevo, política de enlaces, jitter buffer, mezcla, VAD y ráfagas, con pruebas (23)
- [x] T03 Transporte BLE: anuncio 1M + Coded, escaneo, L2CAP, saludo, duplicados, ping, RSSI y PHY
- [x] T04 Audio: modos casco (HFP) y mezcla (A2DP), dos volúmenes, ruta visible
- [x] T05 Interfaz: conexión, hablar (modos, PTT, medidor), grupo (saltos, búfer, pérdidas) y diagnóstico (ruta de audio; enlaces con RSSI, PHY, RTT, kbps, cola y descartes)
- [x] T06 Volumen en la pantalla principal: maestro, voz y multimedia. El maestro mueve los dos conservando la diferencia, y los botones del móvil o del casco mueven el maestro
- [x] T07 Controles de música en la pantalla principal: anterior, reproducir/pausa y siguiente
- [x] T08 Pantalla de ajustes aparte: identidad (nombre, grupo), audio (modo, sensibilidad, micro y salida de voces en mezcla), radio (PHY, largo alcance, calidad, enlaces máximos) e información (capacidades del móvil, id de nodo)
- [x] T09 "Atrás" con la conexión activa manda la app al fondo en vez de cerrarla (cerrarla destruía el motor)

### Bloque 2 — App de grupo usable

- [ ] T10 Servicio en primer plano (`microphone|connectedDevice`) con notificación (PTT, colgar); fuera la pantalla siempre encendida
- [ ] T11 Reconexión en segundo plano y tras apagar y encender el Bluetooth
- [ ] T12 Grupos por QR con clave
- [ ] T13 Cifrado de tramas (AEAD con la clave del grupo, nonce = origen + seq) y Hello autenticado
- [ ] T14 PTT con el botón del casco (MediaSession; en HFP el botón manda órdenes de llamada)
- [ ] T15 Asistente de compatibilidad del casco: grabar, reproducir, medir y proponer modo
- [ ] T16 Escaneo adaptativo: bajo consumo con los enlaces estables

### Bloque 3 — Robustez y calidad

- [ ] T20 Árbol con peso por calidad de enlace (RSSI, RTT, descartes) en vez de solo saltos
- [ ] T21 AAudio si AudioRecord/AudioTrack se quedan cortos de latencia
- [ ] T22 Lista de compatibilidad de cascos (modelo → modo recomendado)
- [ ] T23 Límite de enlaces según el chipset
- [ ] T24 Opus DRED para enlaces con muchas pérdidas

### Bloque 4 — iOS (necesita un Mac con Xcode)

- [ ] T30 `:core` → Kotlin Multiplatform
- [ ] T31 CoreBluetooth L2CAP. El PSM también por característica GATT: en segundo plano iOS no anuncia datos de servicio
- [ ] T32 AVAudioSession (`playAndRecord` + `allowBluetooth` / `allowBluetoothA2DP`)
- [ ] T33 Interfaz (Compose Multiplatform o SwiftUI)

### Bloque 5 — Opcional

- [ ] T40 Transporte por internet cuando hay cobertura, con salto automático a BT

## 8. Aprendizajes

- **Opus desde git:**
  - Su CMake lee `configure.ac`, `Makefile.am` y los `*.mk`, así que hay que copiarlos.
  - Sin el fichero `package_version` no sabe la versión.
  - Sin DRED/OSCE no hacen falta los pesos de `dnn/` (y `dnn/torch` sobra).
  - En Debug compila sin optimizar y es muy lento: `-O2` forzado.
- **`connectGatt`:** las sobrecargas están obsoletas en API 37. Se siguen usando, porque la sustituta no existe en 29–36.
- **"Atrás" en la actividad raíz:** en el emulador (Android 16) cierra la tarea entera. Con el motor en el ViewModel eso colgaba la conversación (T09).
- **Botones de volumen:** cada pulsación sube el número de pasos que decida el sistema, no siempre uno. Por eso el maestro se mueve con la diferencia real de índice.
- **Riesgo abierto:** el BLE entre móviles y el audio Bluetooth al casco comparten la radio del teléfono. Con SCO o A2DP activos, el enlace BLE puede ir más justo. Se mira en "Enlaces": kbps, cola y descartadas.
