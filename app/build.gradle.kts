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
        versionCode = 2
        versionName = "0.1.1"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
}
