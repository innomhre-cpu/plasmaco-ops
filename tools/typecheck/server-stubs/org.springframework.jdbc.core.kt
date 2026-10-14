@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package org.springframework.jdbc.core

fun interface RowMapper<T> { fun mapRow(rs: java.sql.ResultSet, rowNum: Int): T? }
open class JdbcTemplate() {
    constructor(dataSource: javax.sql.DataSource) : this()
    fun <T> query(sql: String, rowMapper: RowMapper<T>, vararg args: Any?): List<T> = TODO()
    fun update(sql: String, vararg args: Any?): Int = TODO()
    fun execute(sql: String) {}
}
