plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "hu.vintedfigyelo"
    compileSdk = 35

    defaultConfig {
        applicationId = "hu.vintedfigyelo"
        minSdk = 26
        targetSdk = 35
        versionCode = runNumber
        versionName = "1.0.$runNumber"
    }

    // Állandó aláíró kulcs, hogy az új verzió a régi fölé telepíthető legyen (a beállítások megmaradnak).
    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = "vintedfigyelo"
            keyAlias = "vintedfigyelo"
            keyPassword = "vintedfigyelo"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false; checkReleaseBuilds = false }
}
