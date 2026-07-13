plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val relayUrl = providers.environmentVariable("MONICA_RELAY_URL")
    .orElse("https://relay-not-configured.invalid")
    .get()
    .trim()
    .trimEnd('/')

android {
    namespace = "no.blckswan.monicakey"
    compileSdk = 35

    defaultConfig {
        applicationId = "no.blckswan.keypair"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.3.0"
        buildConfigField("String", "RELAY_URL", "\"${relayUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    }

    flavorDimensions += "owner"

    productFlavors {
        create("pippi") {
            dimension = "owner"
            applicationId = "no.blckswan.pippikey"
            versionNameSuffix = "-pippi"
            buildConfigField("String", "APP_ROLE", "\"PIPPI\"")
            resValue("string", "app_name", "Pippi Key")
        }

        create("monica") {
            dimension = "owner"
            applicationId = "no.blckswan.monicakey"
            versionNameSuffix = "-monica"
            buildConfigField("String", "APP_ROLE", "\"MONICA\"")
            resValue("string", "app_name", "Monica Key")
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

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
