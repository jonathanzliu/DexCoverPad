plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.dex_touchpad"
    compileSdk = 34

    // Use a workspace-local NDK when present (handy for CI/sandboxes), otherwise
    // fall back to the standard SDK-managed NDK (install with:
    //   sdkmanager "ndk;26.3.11579264").
    ndkVersion = "26.3.11579264"
    val localNdk = rootProject.file(".ndk-dl/android-ndk-r26d")
    if (localNdk.exists()) {
        ndkPath = localNdk.absolutePath
    }

    defaultConfig {
        // Kept distinct from the original wireless-ADB build so the Shizuku edition
        // can be installed alongside it. Change back to "com.example.dex_touchpad"
        // to replace the old app in place.
        applicationId = "com.example.dex_touchpad.shizuku"
        minSdk = 26
        targetSdk = 34
        versionCode = 6
        versionName = "2.4"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/cpp/Android.mk")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        aidl = true
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
        disable.add("Instantiatable")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.coroutines.android)

    testImplementation(libs.junit)
}
