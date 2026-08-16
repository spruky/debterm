// Plain terminal app: no AndroidX, no layouts, no resources — one View, one PTY.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.spruky.debterm"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.spruky.debterm"
        minSdk = 24
        // MUST stay <= 28: Android 10+ forbids exec() of files in the app's data
        // dir for targetSdk >= 29, which would kill every binary in the rootfs.
        // This is the same reason Termux pins 28.
        targetSdk = 28
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // CI has no keystore; debug signing still produces an installable APK.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // The rootfs tarball is already gzipped — don't let aapt touch it.
    androidResources {
        noCompress += listOf("gz", "tar")
    }

    packaging {
        jniLibs {
            // proot + its loader are real ELF executables shipped as lib*.so so
            // the installer drops them into nativeLibraryDir (always exec-able).
            // Never let AGP strip them.
            useLegacyPackaging = true
            keepDebugSymbols += "**/*.so"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = false }
    lint { abortOnError = false }
}

dependencies { }
