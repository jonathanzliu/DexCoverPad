plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.dex_touchpad"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.dex_touchpad"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
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
    }

    sourceSets {
        getByName("main") {
            aidl.srcDirs("src/main/aidl")
            java.srcDirs("src/main/java", "src/main/kotlin")
        }
    }
}

// Ensure AIDL compilation happens before Kotlin compilation
tasks.withName("compileDebugKotlin") {
    dependsOn("generateDebugAidl")
}

tasks.withName("compileReleaseKotlin") {
    dependsOn("generateReleaseAidl")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.coroutines.android)
}
