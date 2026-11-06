import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The release workflow passes the number from the release tag (v1.<number>): it becomes the
// versionCode, which the in-app updater compares with the number in the latest release's tag.
val versionNumber = (findProperty("versionNumber") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "com.docnest.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.docnest.app"
        minSdk = 26
        // 34 keeps the classic layout below the status bar on Android 15+.
        targetSdk = 34
        versionCode = versionNumber
        versionName = "1.$versionNumber"
    }

    // Releases are signed with the key in the repository's secrets: GitHub's build writes KEYSTORE_BASE64
    // to app/release.keystore and passes KEYSTORE_PASSWORD, KEY_ALIAS and KEY_PASSWORD as environment
    // variables. Without them (in Android Studio, say) the debug key is used.
    val releaseKey = file("release.keystore")
    val storePass: String? = System.getenv("KEYSTORE_PASSWORD")
    val alias: String? = System.getenv("KEY_ALIAS")
    val keyPass: String? = System.getenv("KEY_PASSWORD")
    signingConfigs {
        create("release") {
            storeFile = releaseKey
            storePassword = storePass
            keyAlias = alias
            keyPassword = keyPass
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val canSign = releaseKey.exists() &&
                !storePass.isNullOrEmpty() && !alias.isNullOrEmpty() && !keyPass.isNullOrEmpty()
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
