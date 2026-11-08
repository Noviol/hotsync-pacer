plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "fit.hotsync.pacer"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "fit.hotsync.pacer"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

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
    kotlinOptions {
        jvmTarget = "11"
    }
    useLibrary("wear-sdk")
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.play.services.wearable)
    implementation(platform(libs.compose.bom))
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.compose.material)
    implementation(libs.compose.foundation)
    implementation(libs.wear.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.core.splashscreen)

    // ---- Added for HotSync Pacer ------------------------------------------
    // Swipe-to-dismiss navigation between screens.
    implementation("androidx.wear.compose:compose-navigation:1.3.1")
    // Ambient / Always-On Display support (AmbientLifecycleObserver).
    implementation("androidx.wear:wear:1.3.0")
    // collectAsStateWithLifecycle() used to observe the race snapshot.
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    // Coroutines for the foreground service's master clock loop.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // ContextCompat.startForegroundService + NotificationCompat.
    implementation("androidx.core:core-ktx:1.13.1")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM tests (android.jar only has stubs)
    testImplementation("org.json:json:20240303")

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.ui.test.junit4)
    debugImplementation(libs.ui.tooling)
    debugImplementation(libs.ui.test.manifest)
}