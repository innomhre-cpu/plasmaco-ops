@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package org.testcontainers.containers

open class PostgreSQLContainer<SELF : PostgreSQLContainer<SELF>>(dockerImageName: String) {
    fun start() {}
    fun stop() {}
    val jdbcUrl: String get() = TODO()
    val username: String get() = TODO()
    val password: String get() = TODO()
}
