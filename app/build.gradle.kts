import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 签名配置从本地的 keystore.properties 读取（该文件不纳入版本库，见根目录 .gitignore）。
// 没有这个文件时不做签名配置：单测、assembleDebug 等照常可跑，assembleRelease 产出未签名包。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.discipline.control"
    compileSdk = 33

    defaultConfig {
        applicationId = "com.discipline.control"
        minSdk = 26
        targetSdk = 33
        versionCode = 14
        versionName = "0.5.0"
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("appkey") {
                storeFile = file(keystoreProps.getProperty("storeFile") ?: "../keystore/discipline.jks")
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("appkey")
            }
        }
        release {
            isMinifyEnabled = false
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("appkey")
            }
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








