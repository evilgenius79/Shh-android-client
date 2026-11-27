import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure JVM module: a client for the Mosh protocol (terminal state synchronised over encrypted
// UDP). It has no Android dependencies so it can be integration tested against a real
// mosh-server on a plain JDK.
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
    implementation(libs.bouncycastle.prov)

    testImplementation(project(":terminal-core"))
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
