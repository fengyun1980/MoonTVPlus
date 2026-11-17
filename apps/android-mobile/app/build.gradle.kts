plugins {
    id("com.android.application")
}

fun propOrEnv(propName: String, envName: String, defaultValue: String): String {
    return (project.findProperty(propName) as String?)
        ?: System.getenv(envName)
        ?: defaultValue
}

val rawBaseUrl = propOrEnv("BASE_URL", "BASE_URL", "http://192.168.1.10:3000")
val appDisplayName = propOrEnv("APP_NAME", "APP_NAME", "MoonTV")
val versionNameValue = propOrEnv("VERSION_NAME", "VERSION_NAME", "1.0.0")
val versionCodeValue = propOrEnv("VERSION_CODE", "VERSION_CODE", "1").toIntOrNull() ?: 1
val minSdkValue = propOrEnv("MIN_SDK", "MIN_SDK", "23").toIntOrNull() ?: 23

fun escapeJavaString(value: String): String = value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

fun escapeXmlString(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("'", "\\'")
    .replace("\"", "\\\"")

android {
    namespace = "com.fengyun.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fengyun.mobile"
        minSdk = minSdkValue
        targetSdk = 35
        versionCode = versionCodeValue
        versionName = versionNameValue

        buildConfigField("String", "BASE_URL", "\"${escapeJavaString(rawBaseUrl)}\"")
        resValue("string", "app_name", escapeXmlString(appDisplayName))
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        create("release") {
            val storeFilePath = System.getenv("ANDROID_KEYSTORE_PATH")
            if (!storeFilePath.isNullOrBlank() && file(storeFilePath).exists()) {
                storeFile = file(storeFilePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            isMinifyEnabled = false
            val storeFilePath = System.getenv("ANDROID_KEYSTORE_PATH")
            if (!storeFilePath.isNullOrBlank() && file(storeFilePath).exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

dependencies {
    // AndroidX 媒体会话（MediaSession + MediaStyle 自动上岛）
    implementation("androidx.media:media:1.8.0")
    implementation("androidx.core:core:1.13.1")

    // ✅ ExoPlayer —— 原生视频播放器（PiP 100% 不黑屏方案）
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.5.1")
}
