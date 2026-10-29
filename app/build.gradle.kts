import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val keystorePropertiesFile: File = rootProject.file("keystore.properties")
val keystoreProperties = Properties()

if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
} else {
    println("WARNING: keystore.properties file not found in the root directory.")
}

android {
    namespace = "com.mowtiie.supanote"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.mowtiie.supanote"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        manifestPlaceholders["appIcon"] = "@mipmap/ic_launcher"
        manifestPlaceholders["appRoundIcon"] = "@mipmap/ic_launcher_round"

        // Dev pre-fill values are only injected into debug builds (see buildTypes.debug).
        buildConfigField("String", "SUPABASE_URL", "\"\"")
        buildConfigField("String", "SUPABASE_KEY", "\"\"")
    }

    if (keystorePropertiesFile.exists()) {
        signingConfigs {
            create("release") {
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            manifestPlaceholders["appIcon"] = "@mipmap/ic_launcher_debug"
            manifestPlaceholders["appRoundIcon"] = "@mipmap/ic_launcher_debug_round"
            isMinifyEnabled = false
            isShrinkResources = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"

            val props = Properties()
            val localProps = rootProject.file("local.properties")
            if (localProps.exists()) {
                localProps.inputStream().use { props.load(it) }
            }
            buildConfigField("String", "SUPABASE_URL", "\"${props.getProperty("SUPABASE_URL") ?: ""}\"")
            buildConfigField("String", "SUPABASE_KEY", "\"${props.getProperty("SUPABASE_KEY") ?: ""}\"")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.okhttp)
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.swiperefreshlayout)
    implementation(libs.material)
    implementation(libs.preference)
    implementation(libs.room.runtime)
    annotationProcessor(libs.room.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.mockito.core)
    testImplementation(libs.org.json)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}