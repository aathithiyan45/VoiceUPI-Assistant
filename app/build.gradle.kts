plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.voiceupi.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.voiceupi.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    // ── Core ───────────────────────────────────────────────────────────────
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    // ── AppCompat (Theme.AppCompat.NoActionBar used in QRScannerActivity) ──
    implementation("androidx.appcompat:appcompat:1.7.0")

    // ── Activity KTX (registerForActivityResult) ───────────────────────────
    implementation("androidx.activity:activity-ktx:1.9.0")

    // ── CameraX (latest stable: 1.5.1) ────────────────────────────────────
    implementation("androidx.camera:camera-core:1.5.1")
    implementation("androidx.camera:camera-camera2:1.5.1")
    implementation("androidx.camera:camera-lifecycle:1.5.1")
    implementation("androidx.camera:camera-view:1.5.1")

    // ── ML Kit Barcode scanning (latest stable: 17.3.0) ───────────────────
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // ── Tests ──────────────────────────────────────────────────────────────
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // biometric
    implementation("androidx.biometric:biometric:1.1.0")

    // ── ConstraintLayout (NEW — for XML layouts) ───────────────────────────
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")


    implementation("androidx.cardview:cardview:1.0.0")
}