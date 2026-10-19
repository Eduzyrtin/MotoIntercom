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
- **Servicios en primer plano** `microphone` y `connectedDevice`: declaración con vídeo corto (conectar el grupo con la
  pantalla apagada y la notificación que lo mantiene). Guárdalo en `docs/play-videos/`.
- **Ubicación** (`ACCESS_FINE_LOCATION`, solo hasta Android 11): la pide el escaneo Bluetooth en esas versiones; no se usa la ubicación.
- **Clasificación de contenido**, **público objetivo** y **anuncios** (ninguno). Categoría: Comunicación.
