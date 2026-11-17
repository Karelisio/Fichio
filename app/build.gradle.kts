import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.ksp)
}

// Read the single source of truth for the version name from the VERSION file at repo root.
val versionFile = rootProject.file("VERSION")
val versionName: String = if (versionFile.exists()) {
    versionFile.readText().trim().ifEmpty { "0.1.0" }
} else {
    "0.1.0"
}
// versionCode comes from github.run_number on CI, otherwise a local fallback.
val versionCode: Int = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()) ?: 1

// Release signing: read the keystore from CI environment variables (GitHub Secrets)
// or from a local keystore.properties file. Falls back to the debug keystore so that
// `./gradlew assembleRelease` still works locally without any secrets.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
fun resolveKeystorePath(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val f = File(raw)
    return if (f.isAbsolute) raw else rootProject.file(raw).absolutePath
}
val releaseKeystorePath: String? =
    resolveKeystorePath(
        System.getenv("FICHIO_KEYSTORE_PATH")
            ?: keystoreProperties.getProperty("storeFile")
    )
val releaseStorePassword: String =
    System.getenv("FICHIO_KEYSTORE_PASSWORD")
        ?: keystoreProperties.getProperty("storePassword")
        ?: ""
val releaseKeyAlias: String =
    System.getenv("FICHIO_KEY_ALIAS")
        ?: keystoreProperties.getProperty("keyAlias")
        ?: ""
val releaseKeyPassword: String =
    System.getenv("FICHIO_KEY_PASSWORD")
        ?: keystoreProperties.getProperty("keyPassword")
        ?: ""

android {
    namespace = "com.karelisio.fichio"
    compileSdk = 36

    signingConfigs {
        if (releaseKeystorePath != null && File(releaseKeystorePath).exists()) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.karelisio.fichio"
        minSdk = 26
        targetSdk = 36
        versionCode = versionCode
        versionName = versionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // Room schema export location (not committed).
        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
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
        aidl = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*"
            )
        }
    }
}

dependencies {
    // Core + AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.documentfile)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.animation)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // DataStore + WorkManager
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    // Glance (widget)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    // Coil (thumbnails)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)

    // Media3 (audio/video playback)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)

    // Ktor (local transfer server)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.call.logging)

    // Coroutines + serialization
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Archives
    implementation(libs.zip4j)
    implementation(libs.commons.compress)
    implementation(libs.junrar)
    implementation(libs.xz)

    // Shizuku / Shevery
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // QR code
    implementation(libs.zxing.core)

    // Debug tooling
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Tests
    testImplementation(libs.junit)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.android)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
