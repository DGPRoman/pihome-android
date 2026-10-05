plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // The keys of the navigation back stack are saved across process death.
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.dgproman.pihome"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.dgproman.pihome"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // For the version the Account screen shows.
        buildConfig = true
    }

    androidResources {
        // Lists English and Ukrainian to the system, so the language can be picked
        // for this app alone in the phone's settings. Read from res/resources.properties
        // and the values-* folders, so a new translation cannot be left off the list.
        generateLocaleConfig = true
    }

    lint {
        warningsAsErrors = true
        checkDependencies = true
        // These fail a build on the day something new is published, with nothing
        // changed here. Moving a version is Dependabot's to propose; raising the
        // target SDK is a change of its own, made on purpose.
        disable += setOf("AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable", "OldTargetApi")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // Robolectric stands in for Android's file descriptors through a JDK
                // internal that the module system does not export by default.
                it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
            }
        }
    }
}

dependencies {
    implementation(project(":hub-client"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.tink.android)
    // Draws invitation codes on the phone, so the hub never makes an image of a credential.
    implementation(libs.zxing.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // Newer than the ones Compose's test library asks for: the older Espresso
    // injects input through a method Android 17 no longer has.
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.espresso.core)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
