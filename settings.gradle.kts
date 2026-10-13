pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MotoIntercom"
// core: Kotlin puro (protocolo, malla, jitter buffer, mezcla, VAD). Sin android.*: pasará a Kotlin Multiplatform para iOS.
// app: Android (BLE L2CAP, audio, Opus por NDK, interfaz).
include(":core", ":app")
