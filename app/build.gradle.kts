plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.sanabria.motointercom"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.sanabria.motointercom"
        // 29: canales L2CAP de BLE (listenUsingInsecureL2capChannel / createInsecureL2capChannel)
        minSdk = 29
        targetSdk = 37
        versionCode = 2
        versionName = "0.2.0"

        ndk {
            // Móviles reales y el emulador
            abiFilters += setOf("arm64-v8a", "x86_64")
        }
    }

    // Opus (third_party/opus) + el puente JNI
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            // R8: reduce, ofusca y optimiza el código (Play lo pide). Las reglas propias están en proguard-rules.pro
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.zxing.core)
    implementation(libs.play.services.code.scanner)
    implementation(libs.androidx.fragment)
}
