// PLASMACO Operations server: Spring Boot (web + JDBC) on PostgreSQL, schema managed by Flyway.
// The sync and sign-in rules live in framework-free classes (server/core), tested without Spring; the Spring layer
// (server/web) only connects them to HTTP and PostgreSQL.
import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.spring.boot)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Spring Boot's dependency versions as a Gradle platform (a recommendation, not a force), so Kotlin 2.0 and
    // kotlinx-serialization keep the versions the shared modules are built with.
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    implementation(project(":core:domain"))
    implementation(project(":core:api"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.kotlin.reflect)
    runtimeOnly(libs.postgresql)

    testImplementation(platform(SpringBootPlugin.BOM_COORDINATES))
    testImplementation(testFixtures(project(":core:domain")))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.testcontainers.postgresql)
    // Lets the test HTTP client read 401 answers to POST requests (the JDK's HttpURLConnection cannot in streaming mode).
    testImplementation(libs.httpclient5)
    testImplementation(libs.junit)
    testImplementation(kotlin("test-junit"))
    testRuntimeOnly(libs.junit.vintage)
    testRuntimeOnly(libs.postgresql)
}

tasks.test {
    // JUnit 4 tests run on the JUnit Platform through the vintage engine. The PostgreSQL tests use Testcontainers and
    // are skipped (not failed) when no Docker daemon is available.
    useJUnitPlatform()
    testLogging { events("failed", "skipped") }
}

tasks.bootJar {
    archiveFileName.set("plasmaco-server.jar")
}

tasks.jar {
    enabled = false
}
