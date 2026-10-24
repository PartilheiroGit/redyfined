import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlinx.serialization)
}

android {
    namespace = "com.lasvegas.secmess"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.lasvegas.secmess"
        minSdk = 30
        //noinspection OldTargetApi
        targetSdk = 36

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // P3 (2026-10-22): the full app ships arm64-v8a only (A52/A15 are arm64). The emulator test
    // build overrides this with -Pabis=x86_64 so the AVD can still exercise the release APK.
    val fullAbis = (project.findProperty("abis") as String?)
        ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        ?: listOf("arm64-v8a")

    // Bootstrap (GitHub): só telemóveis. O emulador pode ser testado com -Pabis=x86_64.
    val bootAbis = (project.findProperty("abis") as String?)
        ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        ?: listOf("arm64-v8a", "armeabi-v7a")

    flavorDimensions += "edition"
    productFlavors {
        create("calendar") {
            dimension = "edition"
            versionCode = 41
            versionName = "v3.7.2-CALENDAR"
            buildConfigField("boolean", "CALENDAR_ONLY", "true")
            ndk { abiFilters += bootAbis }
        }
        create("full") {
            dimension = "edition"
            // versionName derivado do versionCode: 109 -> v3.7.73, 122 -> v3.7.86.
            // Impede o desalinhamento entre o número do canal e o nome que a app anuncia.
            val code = 128
            versionCode = code
            versionName = "v3.7.${code - 36}-OG-P2P"
            buildConfigField("boolean", "CALENDAR_ONLY", "false")
            ndk { abiFilters += fullAbis }
        }
    }

    signingConfigs {
        create("release") {
            // Credenciais fora do git: keystore.properties (local) ou variáveis de ambiente.
            // O repositório público nunca contém a password da keystore.
            val propsFile = rootProject.file("keystore.properties")
            val props = Properties()
            if (propsFile.exists()) propsFile.inputStream().use { props.load(it) }
            storeFile = file(
                props.getProperty("storeFile")
                    ?: System.getenv("OG_KEYSTORE")
                    ?: "keystore.jks"
            )
            storePassword = props.getProperty("storePassword")
                ?: System.getenv("OG_STORE_PASSWORD") ?: ""
            keyAlias = props.getProperty("keyAlias")
                ?: System.getenv("OG_KEY_ALIAS") ?: "kalendario"
            keyPassword = props.getProperty("keyPassword")
                ?: System.getenv("OG_KEY_PASSWORD") ?: ""
        }
    }

    buildTypes {
        release {
            // P4 default. P3 fallback build: ./gradlew assembleFullRelease -Pminify=false
            val minify = (project.findProperty("minify") as String?)?.toBoolean() ?: true
            isMinifyEnabled = minify
            isShrinkResources = minify
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        // Bootstrap do GitHub: R8 agressivo, sem as keep rules da app completa. É este o APK
        // que vai para o GitHub (calendário + PIN + update + install), não o `release`.
        create("bootstrap") {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-bootstrap.pro"
            )
            signingConfig = signingConfigs.getByName("release")
            matchingFallbacks += listOf("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            // Necessário para o Tor embebido (libtorexec.so é extraído no install).
            useLegacyPackaging = true
        }
        // P3: dex comprimido no APK (legacy). Medir o arranque a frio; se piorar, reverter só isto.
        dex {
            useLegacyPackaging = true
        }
    }
    lint {
        checkReleaseBuilds = false
        disable += "RequestPermissionsResultValidator"
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // Lint: registerForActivityResult (androidx.activity) requires Fragment >= 1.3; a transitive
    // dependency pinned 1.1.0. The app has no fragments; R8 strips this.
    implementation("androidx.fragment:fragment:1.7.1")

    // Security and Crypto
    implementation(libs.bouncycastle)
    implementation(libs.androidx.security.crypto)

    // Networking (Custom WSS)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.websockets)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // Embedded Tor (P2) — runtime 2.4.0 + recursos Tor 408.18.0 (common 2.4.0)
    implementation("io.matthewnelson.kmp-tor:runtime:2.4.0")
    implementation("io.matthewnelson.kmp-tor:resource-exec-tor:408.18.0")
    implementation("io.matthewnelson.kmp-tor:resource-compilation-lib-tor:408.18.0")

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // QR Code
    implementation(libs.zxing.core)
    implementation(libs.zxing.embedded)

    // Location
    implementation(libs.play.services.location)

    // P1: Room, SQLCipher, WebRTC e overrides antigos de coroutines removidos.
    // Eram usados apenas por código morto; ver attic/2026-10-21/MANIFEST.md.
    // As coroutines continuam disponíveis transitivamente via Compose/Ktor.

    testImplementation(libs.junit)
    testImplementation(libs.junit.jupiter)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}