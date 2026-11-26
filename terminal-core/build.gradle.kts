import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure JVM module with no Android dependencies so the emulator can be unit tested on any JDK.
// Compiled against the Java 8 API surface so nothing newer than what Android provides can leak in.
java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget = JvmTarget.JVM_1_8
        freeCompilerArgs.add("-Xjdk-release=1.8")
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}

tasks.test {
    useJUnit()
    testLogging {
        // Print why a test failed, not just that it did; CI logs are often all there is.
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
