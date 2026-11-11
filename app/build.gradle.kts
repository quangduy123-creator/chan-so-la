plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.duy.chanso"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.duy.chanso"
        minSdk = 29          // Android 10+ (cần cho CallScreeningService)
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
    }
    // Khoá ký cố định để các bản sau cài đè được, không phải gỡ app
    signingConfigs {
        create("fixed") {
            storeFile = file("chanso.keystore")
            storePassword = "chanso123"
            keyAlias = "chanso"
            keyPassword = "chanso123"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("fixed") }
        release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("fixed") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
