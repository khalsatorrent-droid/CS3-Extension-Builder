import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Read version from version.properties
val versionProps = Properties().apply {
    val propsFile = rootProject.file("version.properties")
    if (propsFile.exists()) load(propsFile.inputStream())
}

android {
    namespace = "com.khalsatorrent.cs3builder"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.khalsatorrent.cs3builder"
        minSdk = 26
        targetSdk = 34
        versionCode = versionProps.getProperty("android.versionCode", "1").trim().toInt()
        versionName = versionProps.getProperty("android.versionName", "1.0.0").trim()
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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    implementation("com.google.code.gson:gson:2.10.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Document-start script injection into every frame (iframes/embeds included)
    implementation("androidx.webkit:webkit:1.10.0")
    // HTML analysis for selector detection in the code generator
    implementation("org.jsoup:jsoup:1.17.2")

    // Plays captured media urls (HLS and progressive). The WebView cannot decode HLS at all,
    // so without this a .m3u8 stream is unplayable inside the app.
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
}
