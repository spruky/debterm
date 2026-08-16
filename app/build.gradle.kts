// Plain terminal app: no AndroidX, no layouts, no resources — one View, one PTY.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.spruky.debterm"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.spruky.debterm"
        minSdk = 24
        // 35 is safe even though Android 10+ forbids exec() of files in the app's
        // data dir: the only thing this app execs is libproot.so out of
        // nativeLibraryDir, and proot hands every guest binary to its ptrace
        // loader, which maps it instead of exec'ing it. Termux needs 28 because
        // it execs its data dir directly; we never do.
        targetSdk = (findProperty("debterm.targetSdk") as String?)?.toInt() ?: 35
        versionCode = 2
        versionName = "1.1"
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
