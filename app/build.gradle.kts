plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")

    // Optional: only applied if app/google-services.json exists
    id("com.google.gms.google-services") apply false
    id("com.google.firebase.crashlytics") apply false
}

// Optional Firebase integration.
// Open-source builds should work without Firebase configured.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
}

android {
    namespace = "at.saltyy.switchly"
    compileSdk = 36

    defaultConfig {
        applicationId = "at.saltyy.switchly"
        minSdk = 27
        targetSdk = 36

        versionCode = 144
        versionName = "1.4.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    // Unit test dependencies
    testImplementation("junit:junit:4.13.2")

    // Instrumented Android test dependencies
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")

    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")

    // Coroutines helpers (lifecycleScope) + DataStore (for small user prefs)
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    val roomVersion = "2.8.4"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Firebase BOM + Modules
    implementation(platform("com.google.firebase:firebase-bom:34.8.0"))

    // Auth for Google-Login
    implementation("com.google.firebase:firebase-auth")
    // Cloud Firestore (for Sync & Stats)
    implementation("com.google.firebase:firebase-firestore")
    // Crash reporting
    implementation("com.google.firebase:firebase-crashlytics")

    // Google Sign-In / Credential Manager
    implementation("com.google.android.gms:play-services-auth:21.5.0")
    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.5.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    // Play Billing
    implementation("com.android.billingclient:billing-ktx:8.3.0")

    // Play Store in-app update (check for updates)
    implementation("com.google.android.play:app-update-ktx:2.1.0")

    // CameraX
    val camerax = "1.5.2"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // Provides com.google.common.util.concurrent.ListenableFuture for CameraX
    implementation("com.google.guava:guava:33.5.0-android")

    // ML Kit barcode scanning (Play Services variant)
    implementation("com.google.android.gms:play-services-mlkit-barcode-scanning:18.3.1")

    // QR generate (ZXing core)
    implementation("com.google.zxing:core:3.5.4")
}

// fix for duplicated classes: com.intellij:annotations vs org.jetbrains:annotations
configurations.configureEach {
    exclude(group = "com.intellij", module = "annotations")
}

