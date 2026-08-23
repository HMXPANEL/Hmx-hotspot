plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "hmx"
    compileSdk = 35

    val supabaseUrl = findProperty("hmx.supabaseUrl")?.toString()
        ?: "https://qemhnhxlxhnyufmjybgj.supabase.co"
    val supabaseAnonKey = findProperty("hmx.supabaseAnonKey")?.toString()
        ?: "sb_publishable_zuLuuxAul96V3VmK2Cfehg_-bAjwC6q"

    defaultConfig {
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
        applicationId = "hmx.remote.internet"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "1.0.0-rc1"


    }

    signingConfigs {
        create("release") {
            val storePath = System.getenv("HMX_RELEASE_STORE_PATH") ?: ""
            val storePass = System.getenv("HMX_RELEASE_STORE_PASSWORD") ?: ""
            val keyAlias = System.getenv("HMX_RELEASE_KEY_ALIAS") ?: ""
            val keyPass = System.getenv("HMX_RELEASE_KEY_PASSWORD") ?: ""
            if (storePath.isNotBlank() && file(storePath).exists()) {
                storeFile = file(storePath)
                storePassword = storePass
                this.keyAlias = keyAlias
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.android)
    implementation(libs.zxing.core)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.wireguard.tunnel)
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(files("libs/hmx-gateway.aar"))

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
