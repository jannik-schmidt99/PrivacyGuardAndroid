plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.privacyguard"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.privacyguard"
        minSdk = 28
        targetSdk = 36
        versionCode = 6
        versionName = "0.3.0"
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
}
