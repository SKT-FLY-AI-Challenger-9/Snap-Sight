plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

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

        // 백엔드 주소 — 기본은 에뮬레이터→호스트(10.0.2.2). 실기기는 PC LAN IP 로 재정의:
        //   .\gradlew assembleDebug "-PBACKEND_BASE_URL=http://192.168.0.10:8000"
        val backendBaseUrl = (project.findProperty("BACKEND_BASE_URL") as String?)
            ?: "http://10.0.2.2:8000"
        buildConfigField("String", "BACKEND_BASE_URL", "\"$backendBaseUrl\"")
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
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