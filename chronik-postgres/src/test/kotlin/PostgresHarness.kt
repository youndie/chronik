package io.github.youndie.chronik.postgres

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * A real Postgres, started once for the whole test JVM.
 *
 * NOT H2 AND NOT A MOCK, and the reason is the whole point of this module. A mock returns what the
 * test put in it, so it proves nothing about SQL — and the defect a store test exists to catch
 * lives in the SQL. H2's Postgres compatibility mode is cheaper and diverges on exactly what is
 * leaned on here: `SELECT ... FOR UPDATE SKIP LOCKED`, which it does not have.
 *
 * One container rather than one per class: starting Postgres costs a second or two, and a suite
 * that pays that per class is a suite people stop running.
 */
object PostgresHarness {
    // Pinned to a major rather than `latest`: a stand that silently changes its DBMS version
    // answers a different question every few months.
    private const val IMAGE = "postgres:18-alpine"

    // `org.testcontainers.postgresql.PostgreSQLContainer`, not the one in `.containers`: in
    // Testcontainers 2.x the older class and its self-typed generic are deprecated. Nothing warned
    // until the shared conventions turned on -Werror, which is the point of -Werror.
    private val container: PostgreSQLContainer =
        PostgreSQLContainer(DockerImageName.parse(IMAGE)).apply {
            withDatabaseName("chronik")
            withUsername("chronik")
            withPassword("chronik")
            start()
        }

    val jdbcUrl: String get() = container.jdbcUrl
    val username: String get() = container.username
    val password: String get() = container.password

    val database: Database by lazy {
        Database.connect(
            url = container.jdbcUrl,
            driver = "org.postgresql.Driver",
            user = container.username,
            password = container.password,
        )
    }

    /**
     * A table per test, with its own name.
     *
     * chronik ships no DDL, so this is the application's job and the test has to do it too — which
     * incidentally exercises the claim that the table describes itself well enough to be created
     * from its own definition, indexes included.
     */
    fun freshTable(name: String): TimersTable {
        val table = TimersTable(name)
        transaction(database) {
            SchemaUtils.drop(table)
            SchemaUtils.create(table)
        }
        return table
    }
}
