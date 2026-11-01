plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cameng.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cameng.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // Chave fixa para que o SHA-1 registrado no Google Cloud seja sempre o mesmo,
    // independente de onde o APK for compilado (seu PC ou GitHub Actions).
    signingConfigs {
        create("shared") {
            storeFile = file("cameng.keystore")
            storePassword = "cameng123"
            keyAlias = "cameng"
            keyPassword = "cameng123"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
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
        viewBinding = true
        buildConfig = false
    }
}

dependencies {
    val camerax = "1.4.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    implementation("com.google.android.gms:play-services-auth:21.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
