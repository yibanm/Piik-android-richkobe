plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.richkobe.piikandroid"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.richkobe.piikandroid"
        // Android 8.0：兼容老设备。原为 29（系统声音采集所需），现已对 API 29 的
        // 两处调用加运行时守卫，低版本自动降级（不采集系统声音、WiFi 锁用旧模式）。
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "2.4.1" // 跟随内置 Piik 引擎版本
    }

    // 按 ABI 分包：现代 64 位机装 arm64 包，老 32 位设备装 armv7 包，各自只带自己
    // 那套 .so。引擎单个 .so 就有 46MB，做成 universal 包会让所有人都白背另一套架构。
    // 不出 x86_64：build-native.sh 默认不编它（只服务模拟器），且 32 位 x86 无真机需求。
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
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
