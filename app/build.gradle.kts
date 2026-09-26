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
        versionCode = 2
        versionName = "0.1.1"
        multiDexEnabled = true
    }
    // The release key comes from the environment (see .github/workflows/release.yml).
    // Without ANCAMERA_KEYSTORE, assembleRelease gives an unsigned APK.
    val releaseKeystore = System.getenv("ANCAMERA_KEYSTORE")
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("ANCAMERA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANCAMERA_KEY_ALIAS")
                keyPassword = System.getenv("ANCAMERA_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
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
