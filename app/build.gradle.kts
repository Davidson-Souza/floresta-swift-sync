plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.getfloresta.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.getfloresta.sample"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation("org.getfloresta:floresta-android:0.1.0-SNAPSHOT")
    implementation("net.java.dev.jna:jna:5.14.0@aar")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
