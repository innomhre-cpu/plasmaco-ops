// Pure-Kotlin business core shared by the Android app and the server.
// No Android or framework dependency: everything here runs (and is tested) on a plain JVM.
plugins {
    alias(libs.plugins.kotlin.jvm)
    // Shared test fixtures (TestData: real PLASMACO catalogue, clearly-labelled test lines/shifts/actors), used by the
    // domain tests and the server tests.
    `java-test-fixtures`
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(kotlin("test-junit"))
}

tasks.test {
    // Optional integration tests against the original PLASMACO workbooks:
    //   ./gradlew :core:domain:test -Pplasmaco.workbooks=/path/to/folder
    providers.gradleProperty("plasmaco.workbooks").orNull?.let { systemProperty("plasmaco.workbooks", it) }
    testLogging { events("failed"); showStandardStreams = false }
}
