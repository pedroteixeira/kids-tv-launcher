import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// PIN to leave kids mode: `kidsTv.pin=...` in tv/local.properties (gitignored); never committed.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "app.tvdascriancas"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.tvdascriancas"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        buildConfigField("String", "KIDS_PIN", "\"${localProps.getProperty("kidsTv.pin", "1234")}\"")
    }

    // The approved titles live in app/src/main/assets/titulos.json (see README).

    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
}
