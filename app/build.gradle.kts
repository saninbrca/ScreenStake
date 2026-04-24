import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
}

// Read signing credentials from local.properties (not committed to source control)
val localProperties = Properties().also { props ->
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use(props::load)
}

android {
    namespace = "com.detox.app"
    compileSdk {
        version = release(36)
    }

    signingConfigs {
        create("release") {
            val keystorePath = localProperties.getProperty("KEYSTORE_PATH")
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = localProperties.getProperty("KEYSTORE_PASSWORD", "")
                keyAlias = localProperties.getProperty("KEY_ALIAS", "")
                keyPassword = localProperties.getProperty("KEY_PASSWORD", "")
            }
        }
    }

    defaultConfig {
        applicationId = "com.detox.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("String", "STRIPE_PUBLISHABLE_KEY", "\"pk_test_51TGc6D2WIP9KOc5VfDH5lPiXMIWGZP4tKFLgmYhAKr4xssAGImfyUJBX20gzbLJDRK8EWnh9mpntZ4xUMAKDo7KM00r22YWuSO\"")
            buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"71504894920-kqf65vcehl823st306g1ppats413u8l1.apps.googleusercontent.com\"")
        }
        debug {
            buildConfigField("String", "STRIPE_PUBLISHABLE_KEY", "\"pk_test_51TGc6D2WIP9KOc5VfDH5lPiXMIWGZP4tKFLgmYhAKr4xssAGImfyUJBX20gzbLJDRK8EWnh9mpntZ4xUMAKDo7KM00r22YWuSO\"")
            buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"71504894920-kqf65vcehl823st306g1ppats413u8l1.apps.googleusercontent.com\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}



dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation("androidx.compose.material:material-icons-extended")
    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    // Navigation
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)                  // com.google.dagger:hilt-compiler — processes @HiltViewModel, @Inject, etc.
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)         // androidx.hilt:hilt-compiler — processes @HiltWorker and generates HiltWorkerFactory bindings

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth.ktx)
    implementation(libs.firebase.firestore.ktx)
    implementation(libs.firebase.messaging.ktx)
    implementation(libs.firebase.crashlytics.ktx)
    implementation(libs.firebase.analytics.ktx)

    // Google Sign-In
    implementation(libs.play.services.auth)

    // Stripe
    implementation(libs.stripe.android)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    // HTTP
    implementation(libs.okhttp)

    // Image loading
    implementation(libs.coil.compose)

    // Logging
    implementation(libs.timber)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
