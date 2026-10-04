plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "io.github.adman234.openamp"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.adman234.openamp"
        minSdk = 26
        targetSdk = 35
        // CI numbers each build, so a newer build always installs over an older one.
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "0.1.$build"
    }

    // CI signs with one fixed key so that builds update in place. Without the
    // key, as on a developer machine, the default debug key is used.
    val keystorePath = System.getenv("OPENAMP_KEYSTORE")
    if (keystorePath != null) {
        signingConfigs {
            create("stable") {
                storeFile = file(keystorePath)
                storeType = "pkcs12"
                storePassword = System.getenv("OPENAMP_KEYSTORE_PASSWORD")
                keyAlias = "openamp"
                keyPassword = System.getenv("OPENAMP_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            if (keystorePath != null) signingConfig = signingConfigs.getByName("stable")
        }
        release {
            isMinifyEnabled = false
            if (keystorePath != null) signingConfig = signingConfigs.getByName("stable")
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
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.documentfile:documentfile:1.0.1")

    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
}
