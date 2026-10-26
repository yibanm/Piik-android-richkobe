plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.richkobe.piikandroid"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.richkobe.piikandroid"
        minSdk = 29 // Android 10: playback-audio capture and modern MediaCodec keys
        targetSdk = 35
        versionCode = 3
        versionName = "2.4.1" // 跟随内置 Piik 引擎版本
        // Piik's Go binaries are built for 64-bit ARM (and x86_64 for emulators).
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    // Signs with PIIK_KEYSTORE when provided (stable key: updates install over
    // each other); otherwise with the auto-generated debug key.
    val keystore = System.getenv("PIIK_KEYSTORE")
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("PIIK_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("PIIK_KEY_ALIAS")
                keyPassword = System.getenv("PIIK_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (keystore != null) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // The Go executables ship as lib*.so so Android installs them where
        // apps may execute files; they must be extracted, not mapped from the APK.
        jniLibs { useLegacyPackaging = true }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
