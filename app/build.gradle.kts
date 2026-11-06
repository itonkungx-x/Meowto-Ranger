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

    // Release signing remains private: injected by GitHub Actions secrets.\n    val signedKeystore = rootProject.file("release-keystore.jks")\n    if (signedKeystore.isFile) {\n        signingConfigs {\n            create("meowtoRelease") {\n                storeFile = signedKeystore\n                storePassword = System.getenv("MEOWTO_STORE_PASSWORD") ?: error("Missing release store password")\n                keyAlias = System.getenv("MEOWTO_KEY_ALIAS") ?: error("Missing key alias")\n                keyPassword = System.getenv("MEOWTO_KEY_PASSWORD") ?: error("Missing key password")\n            }\n        }\n        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("meowtoRelease")\n    }\n\n    compileOptions {
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
