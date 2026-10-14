package rw.plasmaco.ops.server.web

import org.flywaydb.core.Flyway
import org.junit.Assume
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import javax.sql.DataSource

/**
 * One throwaway PostgreSQL 16 per test JVM (Testcontainers), with the Flyway migrations applied. Tests that need it
 * are skipped, not failed, when no Docker daemon is available.
 */
object PostgresTestDatabase {
    private val container: PostgreSQLContainer<Nothing> by lazy {
        PostgreSQLContainer<Nothing>("postgres:16-alpine").also { pg ->
            pg.start()
            Flyway.configure().dataSource(pg.jdbcUrl, pg.username, pg.password).load().migrate()
        }
    }

    fun assumeDocker() {
        Assume.assumeTrue("Docker is not available; PostgreSQL tests skipped", DockerClientFactory.instance().isDockerAvailable)
    }

    val jdbcUrl: String get() = container.jdbcUrl
    val username: String get() = container.username
    val password: String get() = container.password
    fun dataSource(): DataSource = DriverManagerDataSource(jdbcUrl, username, password)
}
