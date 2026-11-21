import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Default PIN to leave kids mode, for builds from source: `kidsTv.pin=...` in
// local.properties (gitignored). The installer sets the TV's own PIN at runtime.
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
        // Release builds (CI) pass the tag: -PversionName=0.2.0 -PversionCode=200
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("versionName") as String?) ?: "0.1-dev"
        buildConfigField("String", "KIDS_PIN", "\"${localProps.getProperty("kidsTv.pin", "1234")}\"")
    }

    // The approved titles: the TV's own titles.json (sent by the installer), or the example
    // in app/src/main/assets/titles.json (see README).

    // Release signing key, from the environment (CI secrets). Without it the release
    // build is signed with the debug key, which is fine for trying it locally — but a
    // TV only accepts updates signed with the key it was first installed with.
    val keystore = System.getenv("KIDS_TV_KEYSTORE")?.takeIf { it.isNotEmpty() }
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = file(keystore)
            storePassword = System.getenv("KIDS_TV_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KIDS_TV_KEY_ALIAS")
            keyPassword = System.getenv("KIDS_TV_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

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
