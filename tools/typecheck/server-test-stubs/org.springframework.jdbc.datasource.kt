@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package org.springframework.jdbc.datasource

private fun stub(): javax.sql.DataSource = TODO()
class DriverManagerDataSource(url: String, username: String, password: String) : javax.sql.DataSource by stub()
class DataSourceTransactionManager(dataSource: javax.sql.DataSource) : org.springframework.transaction.PlatformTransactionManager
