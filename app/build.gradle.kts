plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.pelico.aicamera"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pelico.aicamera"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 只打主流 ABI，避免 TFLite 的 .so 让 APK 膨胀
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // 模型文件别压缩，避免 asset 解压开销与 mmap 问题。
    // `.task` 必须在里面 —— MediaPipe 从 assets 加载时遇到压缩过的文件会直接失败。
    androidResources {
        noCompress += listOf("onnx", "tflite", "task")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
        }
    }
}

dependencies {
    // ---- Compose ----
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    // ---- CameraX ----
    val cameraX = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")

    // ---- 端侧推理 ----
    // 全部模型都是 tflite，统一走 TFLite：
    //   Places365 场景分类 + NIMA 美学评分
    // 去掉 ONNX Runtime 后 APK 少了约 30 MB
    implementation("org.tensorflow:tensorflow-lite:2.16.1")

    // L1 姿态层。锁死版本号：用 latest.release 会在某次自动升级里引入不兼容
    implementation("com.google.mediapipe:tasks-vision:0.10.14")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
