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
        versionCode = 10
        versionName = "0.10.0"
    }

    // Release signing remains private: injected by GitHub Actions secrets.
    val signedKeystore = rootProject.file("release-keystore.jks")
    if (signedKeystore.isFile) {
        signingConfigs {
            create("meowtoRelease") {
                storeFile = signedKeystore
                storePassword = System.getenv("MEOWTO_STORE_PASSWORD") ?: error("Missing release store password")
                keyAlias = System.getenv("MEOWTO_KEY_ALIAS") ?: error("Missing key alias")
                keyPassword = System.getenv("MEOWTO_KEY_PASSWORD") ?: error("Missing key password")
            }
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("meowtoRelease")
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/**/OSGI-INF/MANIFEST.MF",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE.md"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// On-device Latin OCR for the numeric in-game daily limit (e.g. 5/50).
dependencies {
    implementation("androidx.core:core:1.15.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.github.rhythmcache:adb-kt:v1.0.13")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
