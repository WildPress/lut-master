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
        versionCode = 38
        versionName = "0.29.0"
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

    buildTypes {
        // Release builds are for public sharing: not debuggable, same key as debug so either installs
        // over the other.
        getByName("release") {
            isMinifyEnabled = false
            if (projectKey.exists()) signingConfig = signingConfigs.getByName("debug")
        }
    }

    // -PpublicBuild=true leaves Samsung's Log LUT out of the APK. It's downloaded from Samsung behind a
    // sign-in for personal use, so it must never ship in a public release. Users load it themselves.
    if (providers.gradleProperty("publicBuild").orNull == "true") {
        androidResources {
            ignoreAssetsPattern = "!samsung-log-to-rec709.cube:!.svn:!.git:!.ds_store:!*.scc:.*:!CVS:!thumbs.db:!picasa.ini:!*~"
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
