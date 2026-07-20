import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.google.services)
}

// Read signing credentials from keystore.properties (not committed to source control).
// See keystore.properties.template for the expected keys.
val keystoreProperties = Properties().also { props ->
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use(props::load)
}

android {
    namespace = "com.detox.app"
    compileSdk {
        version = release(36)
    }

    signingConfigs {
        create("release") {
            val keystorePath = keystoreProperties.getProperty("KEYSTORE_PATH")
            if (keystorePath != null) {
                storeFile = rootProject.file(keystorePath)
                storePassword = keystoreProperties.getProperty("KEYSTORE_PASSWORD", "")
                keyAlias = keystoreProperties.getProperty("KEY_ALIAS", "")
                keyPassword = keystoreProperties.getProperty("KEY_PASSWORD", "")
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

        // TODO: Replace with real DSN from sentry.io after project creation
        buildConfigField(
            "String",
            "SENTRY_DSN",
            "\"https://2fb0ac4ecd6bc9af8021ad9e42448eb9@o4511430516277248.ingest.de.sentry.io/4511483693498448\""
        )
        manifestPlaceholders["SENTRY_DSN"] = "https://2fb0ac4ecd6bc9af8021ad9e42448eb9@o4511430516277248.ingest.de.sentry.io/4511483693498448"
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
            // Soft-mode-only Play release: hard legal floor that makes every real-money surface
            // (Hard Mode create, Group buy-in, payout/IBAN, Redemption) unreachable regardless of
            // the fail-open server flags. Flip to true (+ ship an update) to re-enable money; the
            // server flags (hardModeEnabled/groupChallengeEnabled) then resume fine-grained control.
            buildConfigField("boolean", "MONEY_FEATURES_ENABLED", "false")
        }
        debug {
            buildConfigField("String", "STRIPE_PUBLISHABLE_KEY", "\"pk_test_51TGc6D2WIP9KOc5VfDH5lPiXMIWGZP4tKFLgmYhAKr4xssAGImfyUJBX20gzbLJDRK8EWnh9mpntZ4xUMAKDo7KM00r22YWuSO\"")
            buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"71504894920-kqf65vcehl823st306g1ppats413u8l1.apps.googleusercontent.com\"")
            // Debug keeps money features ON so Hard Mode stays fully testable locally with Stripe
            // test keys. Only the release build enforces the soft-only floor.
            buildConfigField("boolean", "MONEY_FEATURES_ENABLED", "true")
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
    androidResources {
        // Keep only the locales the app actually ships (see res/xml/locales_config.xml).
        // Strips the dozens of library locales (Stripe, Firebase, Material) from the APK
        // so they can't leak into the Android 13+ per-app language picker.
        localeFilters += listOf("en", "de")
    }
    lint {
        // EN/DE string parity is a hard invariant (CLAUDE.md "Localization & Dark Mode").
        // ExtraTranslation = key exists only in values-de/ → ResourceNotFoundException CRASH
        // on every non-German device. MissingTranslation = key exists only in values/ →
        // German users silently see English. Both fail the build, never just warn.
        error += "ExtraTranslation"
        error += "MissingTranslation"
    }
}



dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.browser)
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

    // SQLCipher — encrypted Room database (Huawei-safe native AES, no GMS)
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite)

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

    // Lottie (trophy animation)
    implementation("com.airbnb.android:lottie-compose:6.3.0")
    // Konfetti (confetti particle effect)
    implementation("nl.dionsegijn:konfetti-compose:2.0.4")

    // Root detection
    implementation("com.scottyab:rootbeer-lib:0.1.0")

    // Sentry — crash + error tracking (Huawei-safe, no Google Play Services)
    implementation(libs.sentry.android)

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
