plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // The keys of the navigation back stack are saved across process death.
    alias(libs.plugins.kotlin.serialization)
}

/**
 * The version a release tag gives, as `-Ppihome.version=1.2.3` from tag v1.2.3.
 *
 * The version code grows with the version, so each release installs over the
 * one before it: 1.2.3 is 1002003. Each part stays under 1000 for that to hold,
 * and the major version can only go to 999, well within Android's cap.
 */
data class Release(
    val name: String,
    val code: Int,
)

val release: Release? =
    providers.gradleProperty("pihome.version").orNull?.let { name ->
        val parts =
            Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})""")
                .matchEntire(name)
                ?.groupValues
                ?.drop(1)
                ?.map(String::toInt)
                ?: error("pihome.version must be major.minor.patch, each part 0 to 999, not \"$name\"")
        val (major, minor, patch) = parts
        val code = major * 1_000_000 + minor * 1_000 + patch
        // A local build is 1, so even the first release installs over one.
        require(code > 1) { "pihome.version $name is below the first release there can be" }
        Release(name, code)
    }

android {
    namespace = "io.github.dgproman.pihome"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.dgproman.pihome"
        minSdk = 28
        targetSdk = 37
        versionCode = release?.code ?: 1
        versionName = release?.name ?: "0.1.0"
    }

    signingConfigs {
        // Only in the release workflow, which writes the key from its secrets to a
        // file outside the checkout. Anywhere else the release build is unsigned.
        val keystore = providers.environmentVariable("PIHOME_KEYSTORE").orNull
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                // PKCS12, the keytool default, has one password for the store and its key.
                storePassword = providers.environmentVariable("PIHOME_KEYSTORE_PASSWORD").get()
                keyAlias = "pihome"
                keyPassword = storePassword
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
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
    // The home-screen widget, and the refresh that keeps it current while the app is closed.
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.androidx.work.runtime)
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
    testImplementation(libs.androidx.glance.appwidget.testing)
    testImplementation(libs.androidx.work.testing)
    // Newer than the ones Compose's test library asks for: the older Espresso
    // injects input through a method Android 17 no longer has.
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.espresso.core)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
