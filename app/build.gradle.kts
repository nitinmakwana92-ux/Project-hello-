plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // KSP version MUST match Kotlin version (2.0.21)
    id("com.google.devtools.ksp") version "2.0.21-1.0.25"
}

android {
    namespace = "com.example.mycompose.hello"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.mycompose.hello"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
        }
    }
    
    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)

    val room_version = libs.versions.room.get()
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.coil.compose)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    
    implementation("androidx.compose.material:material-icons-extended") 
    implementation("io.coil-kt:coil-svg:2.6.0")

    // Cloud VPS server source is kept in the Android project for deployment,
    // but its JVM/Ktor server libraries must not be packaged into the APK.
    // compileOnly makes the existing CloudVpsServer_FIXED.kt compile without
    // changing or removing its server implementation.
    compileOnly("io.ktor:ktor-server-core-jvm:2.3.12")
    compileOnly("io.ktor:ktor-server-netty-jvm:2.3.12")
    compileOnly("io.ktor:ktor-server-content-negotiation:2.3.12")
    compileOnly("io.ktor:ktor-serialization-gson:2.3.12")
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-core:1.6.3")
    
}