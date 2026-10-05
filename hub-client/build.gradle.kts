import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    // Lets the app's lint, which checks its dependencies, read this module too. Lint
    // knows the app's minSdk, and so catches a call older Android does not have.
    alias(libs.plugins.android.lint)
}

// Everything that talks to the hub, in plain Kotlin with no Android in it, so it is
// tested on the JVM in seconds. It still runs inside the app, on Android 9 and later,
// while being compiled on whatever JDK runs Gradle: pinning the Java API to 17 keeps
// it from reaching anything newer than Android can provide.
kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}
