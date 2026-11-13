plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.watchlite.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.watchlite.launcher"
        minSdk = 26
        // The watch runs Android 8 and the app is sideloaded. Targeting 26 keeps
        // behaviour identical to the device's real platform (no newer background
        // restrictions that the firmware does not have anyway).
        targetSdk = 26
        // CI passes the GitHub run number, so every build installs as an update
        val build = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionCode = 100 + build
        versionName = "1.1.$build"
        resourceConfigurations += listOf("en", "ar")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // One fixed signing key (kept in this private repo) so new APKs update the installed app
    signingConfigs {
        create("watchlite") {
            storeFile = rootProject.file("keystore/watchlite.jks")
            storePassword = "REMOVED"
            keyAlias = "watchlite"
            keyPassword = "REMOVED"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("watchlite") }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key so a release APK can be sideloaded directly.
            signingConfig = signingConfigs.getByName("watchlite")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    androidResources { noCompress += listOf("webp", "ogg", "ttf") }
    lint {
        disable += listOf("ExpiredTargetSdkVersion", "OldTargetApi", "ProtectedPermissions", "QueryAllPackagesPermission")
        checkReleaseBuilds = false
        abortOnError = false
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    // Intentionally no AndroidX / UI libraries: the watch is low on RAM.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
