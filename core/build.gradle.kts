import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Núcleo en Kotlin puro: solo la biblioteca estándar (nada de android.* ni java.*), para convertirlo en módulo
// Kotlin Multiplatform cuando llegue iOS sin reescribirlo.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.kotlin.test)
}
