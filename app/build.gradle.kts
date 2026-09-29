import java.util.Properties
import java.io.FileInputStream

// Gradle plugins applied to this module.
// Version catalogs (libs.versions.toml) resolve the actual plugin versions.
plugins {
    alias(libs.plugins.android.application)  // Android application plugin
    alias(libs.plugins.kotlin.android)       // Kotlin support for Android
    alias(libs.plugins.kotlin.compose)       // Jetpack Compose compiler plugin
}

android {
    namespace = "com.autoclicker.app"
    compileSdk = 35

    // ─── App identity & SDK targets ──────────────────────────────────
    defaultConfig {
        applicationId = "com.autoclicker.app"
        minSdk = 24       // API 24 (Android 7.0) — required for dispatchGesture()
        targetSdk = 35
        versionCode = 3
        versionName = "1.0.2"
    }

    // ─── Release signing config ──────────────────────────────────────
    // Reads keystore credentials from local.properties (not committed to VCS).
    signingConfigs {
        create("release") {
            val localProperties = Properties()
            val localPropertiesFile = rootProject.file("local.properties")
            if (localPropertiesFile.exists()) {
                localProperties.load(FileInputStream(localPropertiesFile))
            }

            storeFile = file("../release-key.jks")
            storePassword = localProperties.getProperty("keystore.password") ?: ""
            keyAlias = localProperties.getProperty("keystore.alias") ?: "my-alias"
            keyPassword = localProperties.getProperty("keystore.password") ?: ""
        }
    }

    // ─── Build types ─────────────────────────────────────────────────
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true       // Enable R8 code shrinking
            isShrinkResources = true     // Remove unused resources
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // ─── Java / Kotlin compiler options ──────────────────────────────
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // ─── Jetpack Compose ─────────────────────────────────────────────
    buildFeatures {
        compose = true
    }
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)

    // Lifecycle
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Compose
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    // Debug
    debugImplementation(libs.androidx.ui.tooling)
}
