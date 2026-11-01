plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vanker.vrefresh"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vanker.vrefresh"
        minSdk = 26
        targetSdk = 35
        versionCode = 13
        versionName = "0.6.6-beta.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
