plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.discipline.control"
    compileSdk = 33

    defaultConfig {
        applicationId = "com.discipline.control"
        minSdk = 26
        targetSdk = 33
        versionCode = 13
        versionName = "0.4.0"
    }

    signingConfigs {
        create("appkey") {
            storeFile = file("../keystore/discipline.jks")
            storePassword = "***REMOVED***"
            keyAlias = "discipline"
            keyPassword = "***REMOVED***"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("appkey")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("appkey")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        abortOnError = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.9.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.recyclerview:recyclerview:1.3.0")
    testImplementation(
        files(
            "libs/junit-4.13.2.jar",
            "libs/hamcrest-core-1.3.jar",
            "libs/json-20231013.jar"
        )
    )
}







