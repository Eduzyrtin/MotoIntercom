# Opus: el código nativo (JNI) busca estas funciones por nombre, así que ni se renombran ni se quitan
-keep class com.sanabria.motointercom.opus.OpusNative {
    native <methods>;
}

# ML Kit (escáner de QR): al arrancar crea sus componentes por reflexión a partir del nombre que trae el manifiesto
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    <init>();
}
