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
        versionCode = 6
        versionName = "2.5.0" // 应用版本独立演进（内置引擎仍为 Piik v2.4.1）
    }

    // 按 ABI 分包：每个架构一个 APK，各自只含自己那套 .so。避免 universal 包让
    // 用户白背用不到的架构——引擎单个 .so 就有 46MB。
    // armeabi-v7a（32 位）走 cgo + NDK clang 构建（Go 对 android/arm 强制外部链接），
    // 见 build-native.sh 的 v7a 分支；三个 ABI 均含 cloudflared（公开邀请可用）。
    // x86_64 面向模拟器（纯 Go 交叉编译）。
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
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
