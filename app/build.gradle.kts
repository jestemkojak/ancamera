plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.ancamera"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ancamera"
        minSdk = 19
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        multiDexEnabled = true
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
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.rtsp.server)
    implementation(libs.rootencoder.library)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
