plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "pl.huber.cadencetofootpod"
    compileSdk = 35

    defaultConfig {
        applicationId = "pl.huber.cadencetofootpod"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "0.5.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
