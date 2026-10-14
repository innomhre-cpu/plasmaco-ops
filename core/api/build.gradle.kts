// Wire contract for /api/v1: request/response DTOs, their mapping to and from the domain model, and the JSON
// settings. Shared by the Android app and the server so both always agree on the format.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:domain"))
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(kotlin("test-junit"))
}
