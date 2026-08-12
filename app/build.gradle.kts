plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.privacyguard"
    compileSdk = 36
    ndkVersion = "27.3.13750724"

    defaultConfig {
        applicationId = "com.example.privacyguard"
        minSdk = 28
        targetSdk = 36
        versionCode = 14
        versionName = "0.7.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("PRIVACYGUARD_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("PRIVACYGUARD_STORE_PASSWORD")
                keyAlias = System.getenv("PRIVACYGUARD_KEY_ALIAS")
                keyPassword = System.getenv("PRIVACYGUARD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (!System.getenv("PRIVACYGUARD_KEYSTORE_PATH").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("com.maxmind.db:maxmind-db:4.1.0")
}
