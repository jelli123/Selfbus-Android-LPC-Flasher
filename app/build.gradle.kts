plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.selfbus.lpcflasher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.selfbus.lpcflasher"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.2-alpha"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Activity Compose
    implementation("androidx.activity:activity-compose:1.9.3")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Serialization (JSON)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // USB Serial
    implementation("com.github.mik3y:usb-serial-for-android:3.7.3")

    // KNX stack for the Bus-Updater (KNXnet/IP). Package: tuwien.auto.calimero
    implementation("com.github.calimero:calimero-core:2.4")
    // SLF4J binding for Android (calimero logs via SLF4J)
    implementation("org.slf4j:slf4j-api:1.7.36")
    implementation("uk.uuid.slf4j:slf4j-android:1.7.30-0")

    // Java 8+ API desugaring (java.time, java.util.zip used by KNX layer)
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
}
