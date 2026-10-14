package rw.plasmaco.ops.server.web

import org.junit.BeforeClass
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import rw.plasmaco.ops.server.ServerStoreContract
import rw.plasmaco.ops.server.core.ServerStore
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** The [ServerStoreContract] against PostgreSQL with the real schema, plus the schema's own guarantees. */
class JdbcServerStoreTest : ServerStoreContract() {
    companion object {
        @JvmStatic
        @BeforeClass
        fun docker() = PostgresTestDatabase.assumeDocker()
    }

    private val dataSource by lazy { PostgresTestDatabase.dataSource() }

    override fun newStore(): ServerStore = JdbcServerStore(JdbcTemplate(dataSource), TransactionTemplate(DataSourceTransactionManager(dataSource)))

    @Test
    fun `the audit log and version history cannot be rewritten`() {
        val jdbc = JdbcTemplate(dataSource)
        for (statement in listOf("UPDATE audit_log SET summary = 'x'", "DELETE FROM audit_log", "TRUNCATE audit_log", "DELETE FROM record_history", "TRUNCATE record_history")) {
            assertFailsWith<Exception>(statement) { jdbc.execute(statement) }
        }
    }
}
