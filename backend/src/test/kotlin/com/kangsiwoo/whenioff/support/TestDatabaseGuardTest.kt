package com.kangsiwoo.whenioff.support

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement
import javax.sql.DataSource

class TestDatabaseGuardTest {
    @Test
    fun `accepts only database names ending with _test`() {
        assertDoesNotThrow { TestDatabaseGuard.requireTestDatabase("when_i_off_test") }
        assertThrows<IllegalStateException> { TestDatabaseGuard.requireTestDatabase("when_i_off") }
        assertThrows<IllegalStateException> { TestDatabaseGuard.requireTestDatabase("when_i_off_test_old") }
        assertThrows<IllegalStateException> { TestDatabaseGuard.requireTestDatabase(null) }
    }

    @Test
    fun `rejects a DataSource connected to the development database`() {
        assertThrows<IllegalStateException> {
            TestDatabaseGuard.DataSourceCheck.postProcessAfterInitialization(dataSourceOn("when_i_off"), "dataSource")
        }
    }

    @Test
    fun `passes a DataSource connected to a test database and ignores other beans`() {
        val ds = dataSourceOn("when_i_off_test")
        assert(TestDatabaseGuard.DataSourceCheck.postProcessAfterInitialization(ds, "dataSource") === ds)
        assert(TestDatabaseGuard.DataSourceCheck.postProcessAfterInitialization("x", "other") == "x")
    }

    private fun dataSourceOn(name: String): DataSource {
        val rs = mock(ResultSet::class.java)
        `when`(rs.next()).thenReturn(true)
        `when`(rs.getString(1)).thenReturn(name)
        val st = mock(Statement::class.java)
        `when`(st.executeQuery("SELECT current_database()")).thenReturn(rs)
        val conn = mock(Connection::class.java)
        `when`(conn.createStatement()).thenReturn(st)
        val ds = mock(DataSource::class.java)
        `when`(ds.connection).thenReturn(conn)
        return ds
    }
}
