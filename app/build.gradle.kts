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
        versionName = "0.1.12-alpha"
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

    lint {
        // Workaround for a Windows file-lock issue in lintVitalAnalyzeRelease
        // ("Der Prozess kann nicht auf die Datei zugreifen") and to keep
        // release builds from failing on pre-existing deprecation warnings.
        checkReleaseBuilds = false
        abortOnError = false
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
    // 2.6 targets Java 17 and uses SLF4J 2.0 (ServiceLoader-based providers).
    implementation("com.github.calimero:calimero-core:2.6")
    // SLF4J 2.0 API; calimero logs are captured by our own SLF4JServiceProvider
    // (com.selfbus.lpcflasher.serial.knx.CalimeroSlf4jProvider) into the app log.
    implementation("org.slf4j:slf4j-api:2.0.17")

    // Hidden-API bypass: needed to force calimero's KNXnet/IP layer to use the
    // IPv4 wildcard on dual-stack devices (see Ipv4Compat). Reaches the blocked
    // java.net internals on modern Android (incl. Android 15+/targetSdk 35).
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")

    // Java 8+ API desugaring (java.time, java.util.zip used by KNX layer)
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
}
