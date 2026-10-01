plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.emreh.snapmemory"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.emreh.snapmemory"
        minSdk = 30
        targetSdk = 35
        versionCode = 4
        versionName = "0.3.0"
    }
    signingConfigs {
        create("ciDebug") {
            storeFile = file("../ci/emrecall-ci-debug.keystore")
            storePassword = "emrecall-ci-password"
            keyAlias = "emrecall-ci"
            keyPassword = "emrecall-ci-password"
        }
    }
    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("ciDebug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
}
