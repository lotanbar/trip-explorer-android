plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.lotanbar.tripexplorer"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.lotanbar.tripexplorer"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        // The Google OAuth client (Desktop type, shared with the PC app) is kept out of git in
        // google_oauth.json at the repo root; without it Drive sync says it is not configured.
        val oauth = rootProject.file("google_oauth.json").takeIf { it.exists() }?.readText().orEmpty()
        fun oauthValue(key: String) = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(oauth)?.groupValues?.get(1).orEmpty()
        buildConfigField("String", "GOOGLE_CLIENT_ID", "\"${oauthValue("client_id")}\"")
        buildConfigField("String", "GOOGLE_CLIENT_SECRET", "\"${oauthValue("client_secret")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.all { it.environment("LIVE", System.getenv("LIVE") ?: "") }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil3.compose)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
