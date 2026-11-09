plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.family.tvquiz.parent"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.family.tvquiz.parent"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
                "META-INF/*.kotlin_module", "META-INF/versions/**", "META-INF/INDEX.LIST",
                "META-INF/FastDoubleParser-*", "META-INF/thirdparty-LICENSE", "META-INF/bigint-*",
            )
        }
    }
}

dependencies {
    // Claude 官方 Java SDK（Kotlin 通用），用于拍照识别出题
    implementation("com.anthropic:anthropic-java:3.2.0")
    // FileProvider：把拍照结果写到 App 自己的文件里
    implementation("androidx.core:core:1.13.1")
}
