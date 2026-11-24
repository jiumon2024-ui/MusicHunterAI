plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Secrets are never committed: GitHub Actions passes the signing settings as environment variables.
val releaseKeyStore = System.getenv("MHA_KEYSTORE_FILE")
val releaseStorePassword = System.getenv("MHA_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("MHA_KEY_ALIAS")
val releaseKeyPassword = System.getenv("MHA_KEY_PASSWORD")
val updateRepo = System.getenv("MHA_REPOSITORY") ?: ""

android {
    namespace = "com.musichunter.ai"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.musichunter.ai"
        minSdk = 23
        targetSdk = 35
        // semver: major * 10000 + minor * 100 + patch. E.g. 0.6.0 -> 600.
        versionCode = 900
        versionName = "0.9.0"
        buildConfigField("String", "UPDATES_REPO", "\"${updateRepo.replace("\"", "")}\"")
    }
    buildFeatures { buildConfig = true }

    if (!releaseKeyStore.isNullOrBlank() &&
        !releaseStorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank()) {
        signingConfigs {
            create("releaseFromCI") {
                storeFile = file(releaseKeyStore)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfigs.findByName("releaseFromCI")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-common:1.11.1")
    implementation("org.jsoup:jsoup:1.18.3")
}
