plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun env(n: String): String? = System.getenv(n)?.takeIf { it.isNotBlank() }

val vCode = (env("VERSION_CODE") ?: "1").toInt()
val ksFile = env("KEYSTORE_FILE") ?: "studio-rais.jks"

android {
    namespace = "id.my.radioislamsragen.studio"
    compileSdk = 35

    defaultConfig {
        applicationId = "id.my.radioislamsragen.studio"
        minSdk = 26
        targetSdk = 35
        versionCode = vCode
        versionName = "1.0.$vCode"
    }

    signingConfigs {
        create("rais") {
            run {
                storeFile = file(ksFile)
                storePassword = env("KEYSTORE_PASSWORD") ?: "studiorais"
                keyAlias = env("KEY_ALIAS") ?: "rais"
                keyPassword = env("KEY_PASSWORD") ?: "studiorais"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("rais")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(bom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
}
