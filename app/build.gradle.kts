import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
}

// Đọc VAPI_API_KEY từ local.properties (gitignored) → BuildConfig. KHÔNG commit key.
val vapiApiKey: String = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}.getProperty("VAPI_API_KEY", "")

// Thông tin ký release. Ưu tiên keystore.properties (build LOCAL, gitignored),
// fallback sang biến môi trường (CI). Nếu KHÔNG có gì → release ký bằng debug key
// (cho ai build thử không có keystore, KHÔNG dùng để phát hành).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingProp(key: String, env: String): String? =
    keystoreProps.getProperty(key) ?: System.getenv(env)

// versionCode/Name tự tăng khi CI truyền -PverCode / -PverName (dùng run_number).
// Build local không truyền → giữ 1 / "1.0" như cũ.
val ciVerCode: Int = (project.findProperty("verCode") as String?)?.toIntOrNull() ?: 1
val ciVerName: String = (project.findProperty("verName") as String?) ?: "1.0"

android {
    namespace = "vn.chat9.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "vn.chat9.app"
        minSdk = 24
        targetSdk = 36
        versionCode = ciVerCode
        versionName = ciVerName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "API_BASE_URL", "\"https://9chat.vn/api/v1/\"")
        buildConfigField("String", "SOCKET_URL", "\"https://9chat.vn\"")
        // Manifest cập nhật (tự-host). App đọc file này lúc mở để biết có bản mới.
        buildConfigField("String", "UPDATE_MANIFEST_URL", "\"https://9chat.vn/app/version.json\"")
        // vapi gateway (backend nghiệp vụ — khác 9chat). Key embed từ local.properties.
        buildConfigField("String", "VAPI_BASE_URL", "\"https://vapi.vn/api/\"")
        buildConfigField("String", "VAPI_API_KEY", "\"$vapiApiKey\"")
    }

    // Ký release bằng key thật nếu có (keystore.properties LOCAL hoặc env CI).
    // Thiếu → bỏ qua, release rơi về debug signing (chỉ để build thử).
    val hasReleaseSigning = signingProp("storePassword", "KEYSTORE_PASSWORD") != null
    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(
                    signingProp("storeFile", "KEYSTORE_FILE") ?: "9chat-release.jks"
                )
                storePassword = signingProp("storePassword", "KEYSTORE_PASSWORD")
                keyAlias = signingProp("keyAlias", "KEY_ALIAS")
                keyPassword = signingProp("keyPassword", "KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "API_BASE_URL", "\"https://9chat.vn/api/v1/\"")
            buildConfigField("String", "SOCKET_URL", "\"https://9chat.vn\"")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // Bỏ qua lintVitalRelease — bước này ăn nhiều thời gian mỗi lần build release
    // trên CI mà không cần cho auto-deploy. Vẫn chạy lint thủ công khi cần.
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    @Suppress("UnstableApiUsage")
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)

    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp.logging)
    implementation(libs.gson)

    // Socket.IO
    implementation(libs.socketio)

    // Image loading
    implementation(libs.coil.compose)

    // QR chuyen khoan: sinh OFFLINE (khong goi API ben thu 3 vi payload chua so TK
    // + so tien + noi dung), quet bang ML Kit, camera preview bang CameraX.
    implementation(libs.zxing.core)
    implementation(libs.mlkit.barcode)
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)

    // DI will use manual singleton pattern (Hilt incompatible with AGP 9.x)

    // Room DB
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Security
    implementation(libs.security.crypto)

    // WebRTC
    implementation("io.getstream:stream-webrtc-android:1.1.1")

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    // Test
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
