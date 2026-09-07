plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rulens.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rulens.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "0.2.2"
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("RU_LENS_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("RU_LENS_STORE_PASSWORD")
                keyAlias = System.getenv("RU_LENS_KEY_ALIAS")
                keyPassword = System.getenv("RU_LENS_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // Bundled/offline Latin-script OCR model. No runtime model download is needed.
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
