plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

fun String.asBuildConfigString(): String = "\"" +
    replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\r", "\\r")
        .replace("\n", "\\n") + "\""

val configuredDebugBackendUrl = (project.findProperty("BACKEND_BASE_URL") as String?)
    ?.trim()
    ?.trimEnd('/')
val configuredReleaseBackendUrl =
    (project.findProperty("SNAPSIGHT_RELEASE_BACKEND_BASE_URL") as String?)
        ?.trim()
        ?.trimEnd('/')
val configuredApiToken = (project.findProperty("SNAPSIGHT_API_TOKEN") as String?).orEmpty()
val enableFaceDebugDumps = (project.findProperty("ENABLE_FACE_DEBUG_DUMPS") as String?)
    ?.toBooleanStrictOrNull() ?: false

android {
    namespace = "com.example.snap_sight"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.snap_sight"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 선택적 로컬 백엔드 토큰. APK에서 추출 가능하므로 배포 인증의 강한 비밀로 간주하면 안 된다.
        buildConfigField("String", "SNAPSIGHT_API_TOKEN", configuredApiToken.asBuildConfigString())
        buildConfigField("boolean", "ENABLE_FACE_DEBUG_DUMPS", enableFaceDebugDumps.toString())
    }

    buildTypes {
        debug {
            // 에뮬레이터/실기기 로컬 개발은 debug 리소스에서만 cleartext를 허용한다.
            //   .\gradlew assembleDebug "-PBACKEND_BASE_URL=http://192.168.0.10:8000"
            val backendUrl = configuredDebugBackendUrl ?: "http://10.0.2.2:8000"
            buildConfigField("String", "BACKEND_BASE_URL", backendUrl.asBuildConfigString())
            buildConfigField("boolean", "ALLOW_CLEARTEXT_BACKEND", "true")
        }
        release {
            // 실제 배포 주소는 별도 속성으로 주입한다. 미설정 빌드는 안전하게 실패하는 예약 도메인을 쓴다.
            val backendUrl = configuredReleaseBackendUrl ?: "https://api.snap-sight.invalid"
            require(backendUrl.startsWith("https://", ignoreCase = true)) {
                "SNAPSIGHT_RELEASE_BACKEND_BASE_URL must use https://"
            }
            buildConfigField("String", "BACKEND_BASE_URL", backendUrl.asBuildConfigString())
            buildConfigField("boolean", "ALLOW_CLEARTEXT_BACKEND", "false")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        // TFLite 모델은 mmap 으로 읽으므로(assets.openFd) 압축되면 안 된다.
        noCompress += "tflite"
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.okhttp)
    implementation(libs.tensorflow.lite)
    // 추론을 GPU로 내려 CPU 점유·발열을 줄인다 (TfLiteYoloDetector — 미지원 기기는 CPU 폴백)
    implementation(libs.tensorflow.lite.gpu)
    implementation(libs.tensorflow.lite.gpu.api) // GpuDelegateFactory.Options

    implementation(libs.mlkit.face.detection)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
