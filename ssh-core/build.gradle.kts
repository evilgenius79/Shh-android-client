import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure JVM module: everything SSH related that does not need Android lives here so it can be
// integration tested against a real SSH server on a plain JDK.
java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_1_8
        freeCompilerArgs.add("-Xjdk-release=1.8")
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(libs.sshj)
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.slf4j.api)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mina.sshd.core)
    testImplementation(libs.mina.sshd.sftp)
    testRuntimeOnly(libs.slf4j.nop)
}

tasks.test {
    useJUnit()
    testLogging {
        // Print why a test failed, not just that it did; CI logs are often all there is.
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
