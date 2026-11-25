import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

/**
 * Release imza açarı layihədən KƏNARDA saxlanılır (parollar koda/git-ə düşməsin):
 *   ~/EasyConnect-signing/keystore.properties  (storeFile, storePassword, keyAlias, keyPassword)
 * Başqa yer üçün: EASYCONNECT_SIGNING mühit dəyişəni ilə .properties faylının yolunu verin.
 * Fayl yoxdursa, release build imzasız qalır (debug build-lərə təsir etmir).
 */
val signingProps: Properties? = run {
    val path = System.getenv("EASYCONNECT_SIGNING")
        ?: "${System.getProperty("user.home")}/EasyConnect-signing/keystore.properties"
    val f = file(path)
    if (f.isFile) Properties().apply { f.inputStream().use { load(it) } } else null
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "az.easyconnect.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "az.easyconnect.mobile"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "0.6.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (signingProps != null) {
            create("release") {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    // full: Google Play üçün, Accessibility ilə uzaqdan idarə.
    // lite: APK ilə paylamaq üçün — Accessibility yoxdur, Play Protect bloklamır.
    flavorDimensions += "dist"
    productFlavors {
        create("full") {
            dimension = "dist"
            buildConfigField("boolean", "REMOTE_CONTROL", "true")
        }
        create("lite") {
            dimension = "dist"
            versionNameSuffix = "-lite"
            buildConfigField("boolean", "REMOTE_CONTROL", "false")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    // Tətbiq daxilində QR skaner (telefonun öz kamerası linki açmasa)
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
