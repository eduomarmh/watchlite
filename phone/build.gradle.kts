plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.watchlite.companion"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.watchlite.companion"
        minSdk = 26
        // 33: no foreground-service-type requirements of API 34; fine for a sideloaded app.
        targetSdk = 33
        // CI passes the GitHub run number, so every build installs as an update
        val build = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionCode = 1000 + build   // new repo restarted run numbers: 1000+ keeps every build above the old 1xx ones
        versionName = "1.2.$build"
        resourceConfigurations += listOf("en", "ar")
    }
    // One fixed signing key (kept in this private repo) so new APKs update the installed app
    signingConfigs {
        create("watchlite") {
            // the signing key is NOT in the repo: CI writes it from the WL_KEYSTORE_B64 secret,
            // locally put your copy at keystore/watchlite.jks (git-ignored)
            storeFile = rootProject.file("keystore/watchlite.jks")
            storePassword = System.getenv("WL_KEYSTORE_PASSWORD") ?: (project.findProperty("wlKeystorePassword") as String?) ?: ""
            keyAlias = "watchlite"
            keyPassword = System.getenv("WL_KEYSTORE_PASSWORD") ?: (project.findProperty("wlKeystorePassword") as String?) ?: ""
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("watchlite") }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("watchlite")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { checkReleaseBuilds = false; abortOnError = false }
}

dependencies {
    // OpenStreetMap map view: no Google services needed (works on Huawei without GMS)
    implementation("org.osmdroid:osmdroid-android:6.1.20")
}
