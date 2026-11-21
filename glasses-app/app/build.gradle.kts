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

    testImplementation(libs.json)
    testImplementation(libs.junit)
}
