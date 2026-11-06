plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.itonkung.meowtoranger"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.itonkung.meowtoranger"
        minSdk = 30
        targetSdk = 35
        versionCode = 8
        versionName = "0.8.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// On-device Latin OCR for the numeric in-game daily limit (e.g. 5/50).
dependencies {
    implementation("androidx.core:core:1.15.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
