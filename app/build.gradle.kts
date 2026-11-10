plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val media3 = "1.11.1"

android {
    namespace = "app.lutapply"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.lutapply"
        minSdk = 26
        targetSdk = 35
        versionCode = 18
        versionName = "0.13.2"
    }

    // Debug builds use the project's own key when it's present (git-ignored), so builds from Docker
    // and from Android Studio on any machine sign the same way and install over each other.
    val projectKey = rootProject.file(".signing/debug.keystore")
    if (projectKey.exists()) {
        signingConfigs.getByName("debug") {
            storeFile = projectKey
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-transformer:$media3")
    implementation("androidx.media3:media3-effect:$media3")
    implementation("androidx.media3:media3-common:$media3")
    implementation("androidx.media3:media3-extractor:$media3")
    testImplementation("junit:junit:4.13.2")
}
