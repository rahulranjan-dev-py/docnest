import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// GitHub passes its build number, so every release has a higher versionCode than the one before.
// The in-app updater compares it with the number in the latest release's tag (v1.<build number>).
val buildNumber = (findProperty("buildNumber") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "com.docnest.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.docnest.app"
        minSdk = 26
        // 34 keeps the classic layout below the status bar on Android 15+.
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.$buildNumber"
    }

    // Releases are signed with app/release.keystore, which is locked with the SIGNING_PASSWORD secret
    // that only GitHub's build can read. Without that password (in Android Studio, say) the debug key is used.
    val releaseKey = file("release.keystore")
    val signingPassword: String? = System.getenv("SIGNING_PASSWORD")
    signingConfigs {
        create("release") {
            storeFile = releaseKey
            storeType = "pkcs12"
            storePassword = signingPassword
            keyAlias = "docnest"
            keyPassword = signingPassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val canSign = releaseKey.exists() && !signingPassword.isNullOrEmpty()
            signingConfig = signingConfigs.getByName(if (canSign) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    val camerax = "1.4.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
}
