plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.mcp.injector"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mcp.injector"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        compose = true
    }
    packaging {
        resources.excludes += setOf(
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
            "META-INF/versions/9/previous-compilation-data.bin",
            "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            "META-INF/MANIFEST.MF",
        )
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.navigation:navigation-compose:2.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Dex 注入/预检（任务 C：DexOps）依赖 dexlib2
    implementation("org.smali:dexlib2:2.5.2")
    // SettingsRepository DataStore 持久化依赖
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    // ApkSigner：APK 签名（apksig）+ 证书生成（bouncycastle）
    implementation("com.android.tools.build:apksig:8.5.2")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
}
