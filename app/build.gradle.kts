import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Read GROQ_API_KEY from local.properties (git-ignored) → BuildConfig.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val groqKey: String = (localProps.getProperty("GROQ_API_KEY") ?: System.getenv("GROQ_API_KEY") ?: "")

android {
    namespace = "com.wingman.assist"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.wingman.assist"
        minSdk = 30            // takeScreenshot() needs API 30+
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        buildConfigField("String", "GROQ_API_KEY", "\"$groqKey\"")
    }

    buildFeatures { buildConfig = true }

    sourceSets["main"].java.srcDirs("src/main/kotlin")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // org.json ships with Android; no dependency needed.
}
