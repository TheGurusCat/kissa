plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release builds pass -PappVersion=0.2; versionCode is derived so it always increases (0.2.1 -> 201).
val appVersion = (findProperty("appVersion") as String?) ?: "0.1"
val appVersionCode = appVersion.split(".").map { it.toInt() }.let { parts ->
    parts.getOrElse(0) { 0 } * 10000 + parts.getOrElse(1) { 0 } * 100 + parts.getOrElse(2) { 0 }
}

android {
    namespace = "com.theguruscat.kissa"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.theguruscat.kissa"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersion
        // GitHub repo ("owner/name") whose releases the app checks for updates.
        buildConfigField("String", "UPDATE_REPO", "\"TheGurusCat/kissa\"")
        // Cloudflare Worker that calls Reddit's API for the app (source in /relay).
        // Google Cloud "Web application" OAuth client ID (the relay's GOOGLE_CLIENT_ID); empty hides "Continue with Google".
        buildConfigField("String", "GOOGLE_CLIENT_ID", "\"98321916650-qe2ok27ie78hh0cpsmvaip0a1m1dabdd.apps.googleusercontent.com\"")
        buildConfigField("String", "RELAY_URL", "\"https://kissa-relay.abpro.workers.dev\"")
    }

    // Every release must be signed with the same key, or Android refuses to install it as an update.
    signingConfigs {
        create("release") {
            val keystore = System.getenv("SIGNING_KEYSTORE")
            if (keystore != null) {
                storeFile = file(keystore)
                storeType = "pkcs12"
                storePassword = System.getenv("SIGNING_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (System.getenv("SIGNING_KEYSTORE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // "Continue with Google" through Android's Credential Manager.
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
}
