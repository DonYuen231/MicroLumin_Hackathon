plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "app.recall.glasses"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "app.recall.glasses"
        minSdk = 28
        targetSdk = 28
        versionCode = 1
        versionName = "0.1"

        // Device and emulator ABIs.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Never add glasses-app/libs here.
    implementation(libs.nanohttpd)

    // Offline keyword listening. The model is pushed with adb and is not in the APK or in Git.
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")

    testImplementation(libs.json)
    testImplementation(libs.junit)
}
