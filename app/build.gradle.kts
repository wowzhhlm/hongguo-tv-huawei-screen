import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
}

val legacyAudio = providers.gradleProperty("legacyAudio").map { it.toBooleanStrict() }.getOrElse(false)
if (!legacyAudio) layout.buildDirectory.set(file("build/platform"))

val signingProperties =
    Properties().apply {
        val config = rootProject.file("keystore.properties")
        if (config.exists()) config.inputStream().use { load(it) }
    }

fun signingValue(env: String, key: String): String? =
    System.getenv(env) ?: signingProperties.getProperty(key)

android {
    namespace = "com.hongguotv.nativeapp"
    compileSdk = 36
    defaultConfig {
        // 独立包名：避免与智慧屏上可能已装的官方 hongguoTV 签名冲突（"应用未安装"）。
        applicationId = "com.hongguotv.huawei.screen"
        testInstrumentationRunner = "com.hongguotv.nativeapp.LivePlaybackInstrumentation"
        // 大屏显示名称
        manifestPlaceholders["appLabel"] = "智慧屏短剧"
        minSdk = 21
        if (!legacyAudio) minSdk = 23
        buildConfigField("boolean", "LEGACY_AUDIO", legacyAudio.toString())
        // 兼容项：HarmonyOS 4.3 的 Android 兼容层约为 Android 12（API 31）。
        // targetSdk 36 会触发新一代系统行为约束，兼容层未必完整支持；降到 30 更稳。
        targetSdk = 30
        versionCode = System.getenv("HONGGUOTV_VERSION_CODE")?.toInt() ?: 35
        versionName = (System.getenv("HONGGUOTV_VERSION_NAME") ?: "0.16.14") + "-huawei-screen"
    }
    // 兼容项：移除 ABI 分包，只产出一个 universal APK。
    // 分 ABI 包一旦下错架构（智慧屏多为 arm64-v8a）就会报"解析失败/应用未安装"。
    // 本应用默认无 JNI 库（不启用 legacyAudio 时不含 FFmpeg .so），天然架构无关。
    // 侧载应用（不经 Google Play），targetSdk 为智慧屏兼容层刻意降低，
    // 禁用仅面向 Play 商店的 targetSdk 过期检查，避免阻断本地编译。
    lint {
        disable += "ExpiredTargetSdkVersion"
        abortOnError = false
    }
    buildFeatures { buildConfig = true }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // API 21/22 cases run in the compatibility build; the platform APK cannot install there.
            if (!legacyAudio) it.systemProperty("robolectric.enabledSdks", (23..36).joinToString(","))
            it.systemProperty(
                "robolectric.dependency.repo.url",
                "https://repo.maven.apache.org/maven2",
            )
        }
    }
    signingConfigs {
        create("delivery") {
            storeFile = signingValue("HONGGUOTV_KEYSTORE", "storeFile")?.let { file(it) }
            storePassword = signingValue("HONGGUOTV_STORE_PASSWORD", "storePassword")
            keyAlias = signingValue("HONGGUOTV_KEY_ALIAS", "keyAlias")
            keyPassword = signingValue("HONGGUOTV_KEY_PASSWORD", "keyPassword")
            // 兼容项：同时开启 v1 与 v2 签名。
            // 华为电视/智慧屏这类定制安装器对 v1(JAR signing) 兼容性最好；
            // 只用 v2/v3 在部分老安装器上会被判为无证书而"解析失败"。
            isV1SigningEnabled = true
            isV2SigningEnabled = true
            enableV3Signing = false
        }
    }
    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["appLabel"] = "智慧屏短剧(调试)"
        }
        getByName("release") {
            signingConfig = signingConfigs.getByName("delivery")
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += setOf("META-INF/versions/**", "META-INF/*.kotlin_module") }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.14.1") {
        // API 23 tests use legacy graphics, without the cross-platform native renderer.
        exclude(group = "org.robolectric", module = "nativeruntime-dist-compat")
    }
    implementation("androidx.core:core:1.8.0")
    implementation("com.google.zxing:core:3.5.3")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    if (legacyAudio) implementation(project(":decoder-ffmpeg"))
    implementation(project(":core")) { exclude(group = "org.json") }
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
}
