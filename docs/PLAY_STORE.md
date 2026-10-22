# Google Play: MotoIntercom

Paquete `com.sanabria.motointercom` · móvil · ficha en [`fastlane/metadata/android/`](../fastlane/metadata/android)
(formato estándar, ver §8 de `HERRAMIENTAS/GUIA_PROYECTOS.md`). Idiomas: los 9 de la app; `en-US` es el predeterminado.

- **Precio**: gratuita. También se descarga gratis desde GitHub (Releases).

## Qué subir y dónde (Play Console › Crecimiento › Presencia en la tienda › Ficha principal)

1. **Idiomas**: Gestionar idiomas → añade `es-ES`, `fr-FR`, `de-DE`, `it-IT`, `pt-PT`, `ca`, `eu-ES`, `gl-ES`. Pega en cada uno
   `title.txt`, `short_description.txt` y `full_description.txt` de su carpeta.
2. **Icono**: `en-US/images/icon.png` (dibujado a partir de `ic_launcher_foreground.xml` sobre `#E07000`).
3. **Gráfico de funciones** (obligatorio): `en-US/images/featureGraphic.png` (vale para todos los idiomas).
4. **Capturas de teléfono**: las 4 de `en-US/images/phoneScreenshots/` (1080×1920), hechas con
   `HERRAMIENTAS/tools/PlayArt.java` a partir de `docs/media/shot-*.png`. Para más nitidez, regenerarlas desde capturas
   del emulador a resolución completa.
5. **Novedades** de cada versión: `<idioma>/changelogs/<versionCode>.txt`.

## Reglas de la ficha

- Sin marcas de intercomunicadores ni de cascos (nada de nombres comerciales), ni comparaciones con apps de chat concretas.
- El alcance se dice con franqueza: 30 a 100 m por enlace.

## Antes de publicar (lo que Play pedirá)

- **Política de privacidad (URL)**: obligatoria. El README ya describe la privacidad.
- **Seguridad de los datos**: sin permiso de internet. La voz va cifrada entre los móviles del grupo y no se graba ni se
  guarda. Se puede declarar «no se recogen datos».
- **Servicios en primer plano** `microphone` y `connectedDevice` (`IntercomService`): declaración con vídeo corto. Guárdalo
  en `docs/play-videos/` (ignorado por git), súbelo a YouTube como «No listado» y pega el mismo enlace en los dos campos.
  Guion del vídeo: abrir la app, pulsar *Connect*, mostrar la notificación persistente, salir a Inicio, apagar la pantalla,
  encenderla y comprobar que la notificación sigue; parar desde la app. Si se enseña la autoconexión: con el casco
  elegido en Ajustes y la app cerrada, encender el casco y mostrar que solo sale la notificación (Desconectar / Abrir).
  - `FOREGROUND_SERVICE_CONNECTED_DEVICE`: tarea «Transferencia continua de datos a un dispositivo externo». Texto: *the app
    keeps a continuous Bluetooth LE link with the other phones in the group and sends and receives encrypted audio in real
    time; it must keep running with the screen off, and a persistent notification lets the user stop it at any time.*
  - `FOREGROUND_SERVICE_MICROPHONE`: tarea «Entrada de audio de fondo». Texto: *the app captures the rider's voice from the
    helmet microphone to send it to the group while riding with the phone in a pocket; capture only starts when the user taps
    Connect or, if the user turned on auto-connect and picked their helmet, when that helmet connects to the phone; a
    persistent notification is always visible with a Disconnect button, and audio is never recorded or stored.*
- **Ubicación** (`ACCESS_FINE_LOCATION`, solo hasta Android 11): la pide el escaneo Bluetooth en esas versiones; no se usa la ubicación.
- **Clasificación de contenido**, **público objetivo** y **anuncios** (ninguno). Categoría: Comunicación.
