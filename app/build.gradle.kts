plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.glasslauncher.tv"
    compileSdk = 36
    defaultConfig {
        applicationId = "tv.darren.glassio"
        minSdk = 26
        targetSdk = 35
        versionCode = 38
        versionName = "1.9.3-d14"
        // Trakt client ID: from the environment (GitHub Actions secret TRAKT_CLIENT_ID), never from the repo.
        // Empty in a local build without it; the app then shows "Trakt not configured".
        // Only leading/trailing whitespace and newlines are removed; every other character is kept (Trakt IDs can contain - and _).
        // javaString() escapes the value for the generated Java source instead of dropping characters.
        fun javaString(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""
        val traktClientId = (System.getenv("TRAKT_CLIENT_ID") ?: "").trim()
        buildConfigField("String", "TRAKT_CLIENT_ID", javaString(traktClientId))
        // Must match the redirect URI registered in the Trakt app exactly. Override with env TRAKT_REDIRECT_URI.
        val traktRedirect = (System.getenv("TRAKT_REDIRECT_URI") ?: "").trim().ifBlank { "http://localhost" }
        buildConfigField("String", "TRAKT_REDIRECT_URI", javaString(traktRedirect))
    }
    // Release signing comes only from environment variables (set by CI from repository secrets).
    // Without them the release APK is built unsigned. No keystore or password lives in this repo.
    val storeFilePath = System.getenv("SIGNING_STORE_FILE")
    if (!storeFilePath.isNullOrBlank()) {
        signingConfigs.create("release") {
            storeFile = file(storeFilePath)
            storePassword = System.getenv("SIGNING_STORE_PASSWORD")
            keyAlias = System.getenv("SIGNING_KEY_ALIAS")
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
}
