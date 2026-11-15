plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.apkshare"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.apkshare"
        minSdk = 26
        targetSdk = 35
        // Le numéro de build GitHub augmente à chaque compilation : indispensable pour les mises à jour
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0.$versionCode"
    }

    // Clé de signature fixe : sans elle, chaque build GitHub est signé différemment
    // et Android refuse d'installer la mise à jour par-dessus l'ancienne version.
    signingConfigs {
        create("shared") {
            storeFile = file("shared.keystore")
            storePassword = "android"
            keyAlias = "shared"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
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
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.android.gms:play-services-nearby:19.3.0")
}
