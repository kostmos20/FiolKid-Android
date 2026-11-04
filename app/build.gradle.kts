import java.net.URI
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.GZIPInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("app.cash.paparazzi")
}

/**
 * sing-box для VPN по подписке — тот же, что в версии для компьютера. Готовые сборки для Android с GitHub
 * (SagerNet/sing-box), проверяем по SHA-256 и кладём в библиотеки приложения как libsingbox.so. Скачанное
 * хранится в Android/.deps (в git не попадает: файлы по 80 МБ).
 */
val singBoxVersion = "1.14.3"
val singBoxBuilds = mapOf(
    "arm64-v8a" to ("arm64" to "149569b5dbf01958e6598beb3a13078d6fc0e353ef06f4aff85f5f28bc23593b"),
    "armeabi-v7a" to ("arm" to "0c0df4fbf85656896d5b604e9eb4e556c1a3f68dd615b0ece3f9f6bd5adab5d6"),
)
val singBoxLibs = layout.buildDirectory.dir("sing-box/jniLibs").get().asFile

val fetchSingBox by tasks.registering {
    val cache = rootProject.file(".deps")
    outputs.dir(singBoxLibs)
    inputs.property("version", singBoxVersion)
    doLast {
        fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
        cache.mkdirs()
        for ((abi, build) in singBoxBuilds) {
            val (arch, hash) = build
            val name = "sing-box-$singBoxVersion-android-$arch"
            val archive = File(cache, "$name.tar.gz")
            if (!archive.exists() || sha256(archive) != hash) {
                logger.lifecycle("Скачиваю $name")
                URI("https://github.com/SagerNet/sing-box/releases/download/v$singBoxVersion/$name.tar.gz").toURL()
                    .openStream().use { input -> archive.outputStream().use { input.copyTo(it) } }
                check(sha256(archive) == hash) { "$name.tar.gz: не та контрольная сумма" }
            }
            val target = File(singBoxLibs, "$abi/libsingbox.so")
            if (target.exists()) continue
            target.parentFile.mkdirs()
            // tar: заголовки по 512 байт (имя — первые 100, размер в восьмеричном виде — с 124-го)
            GZIPInputStream(archive.inputStream().buffered()).use { tar ->
                val header = ByteArray(512)
                while (tar.readNBytes(header, 0, 512) == 512 && header[0] != 0.toByte()) {
                    val entry = String(header, 0, 100).trimEnd('\u0000')
                    val size = String(header, 124, 12).trim('\u0000', ' ').toLong(8)
                    val padded = (size + 511) / 512 * 512
                    if (entry.endsWith("/sing-box")) {
                        target.outputStream().use { out ->
                            var left = size
                            val buffer = ByteArray(64 * 1024)
                            while (left > 0) {
                                val n = tar.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                                check(n > 0) { "$name.tar.gz оборвался" }
                                out.write(buffer, 0, n)
                                left -= n
                            }
                        }
                        tar.skipNBytes(padded - size)
                    } else tar.skipNBytes(padded)
                }
            }
            check(target.exists() && target.length() > 1_000_000) { "В $name.tar.gz нет sing-box" }
        }
    }
}

android {
    namespace = "com.fiolkid.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fiolkid.app"
        minSdk = 24
        targetSdk = 35
        // Номер для Android считается из версии: 0.2.0 → 200, 1.4.12 → 10412
        versionName = "0.7.0"
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

    // sing-box (VPN по подписке) запускается как программа: Android разрешает это только из распакованных библиотек
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // Готовая сборка sing-box — как есть, без «урезания» отладочных данных
            keepDebugSymbols += "**/libsingbox.so"
        }
    }
    sourceSets["main"].jniLibs.srcDir(singBoxLibs)
}

tasks.named("preBuild") { dependsOn(fetchSingBox) }

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
