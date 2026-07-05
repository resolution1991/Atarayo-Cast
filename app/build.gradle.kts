import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) {
        file.inputStream().use { load(it) }
    }
}

val aircastDepsDir: String? =
    providers.gradleProperty("aircastDepsDir").orNull
        ?: localProperties.getProperty("aircast.deps.dir")
        ?: System.getenv("AIRCAST_DEPS_DIR")

android {
    namespace = "com.atarayocast.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.atarayocast.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 5
        versionName = "0.4.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                // C/C++ flags are managed entirely by CMakeLists.txt to avoid
                // leaking C++-only flags (e.g. -std=c++17) into C subprojects like OpenSSL
                val cmakeArgs = mutableListOf("-DANDROID_STL=c++_shared")
                aircastDepsDir?.takeIf { it.isNotBlank() }?.let {
                    cmakeArgs += "-DAIRCAST_DEPS_DIR=$it"
                }
                arguments(*cmakeArgs.toTypedArray())
            }
        }

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.media)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.material)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)

    // DLNA is implemented with native UPnP stack (no external dependency)
    // SSDP multicast + SOAP over HTTP are handled directly via Android APIs
}
