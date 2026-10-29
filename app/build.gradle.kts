import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("app.cash.paparazzi")
}

android {
    namespace = "com.fiolkid.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fiolkid.app"
        minSdk = 24
        targetSdk = 35
        // Номер для Android считается из версии: 0.2.0 → 200, 1.4.12 → 10412
        versionName = "0.2.0"
        versionCode = versionName!!.split('.').map(String::toInt).let { (a, b, c) -> a * 10000 + b * 100 + c }

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            ndkBuild {
                targets += listOf("hev-socks5-tunnel", "byedpi")
                // JNI hev-socks5-tunnel регистрирует свои методы на классе com.fiolkid.app.engine.Tunnel
                cFlags += listOf("-DPKGNAME=com/fiolkid/app/engine", "-DCLSNAME=Tunnel")
            }
        }
    }

    ndkVersion = "27.2.12479018"
    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    // Постоянный ключ подписи лежит вне репозитория (F:\Program\FiolKid-tools). Обновление ставится поверх
    // старой версии, только если подписано тем же ключом — терять его нельзя.
    val signing = Properties().apply {
        val file = File("F:/Program/FiolKid-tools/android-signing.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }
    signingConfigs {
        create("fiolkid") {
            if (signing.isNotEmpty()) {
                storeFile = File(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fiolkid")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val compose = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(compose)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
}
