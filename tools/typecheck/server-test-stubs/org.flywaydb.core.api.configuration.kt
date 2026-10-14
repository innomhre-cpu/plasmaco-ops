@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package org.flywaydb.core.api.configuration

class FluentConfiguration {
    fun dataSource(url: String, user: String, password: String): FluentConfiguration = TODO()
    fun dataSource(dataSource: javax.sql.DataSource): FluentConfiguration = TODO()
    fun load(): org.flywaydb.core.Flyway = TODO()
}
