plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.family.tvquiz"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.family.tvquiz"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-poc"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
