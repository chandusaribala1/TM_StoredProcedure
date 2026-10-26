package com.tm.storedprocedure.execution;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/** Session advisory lock spans independently committed per-case transactions. */
@Component
public class PostgresRunLock {
    private final DataSource dataSource;
    public PostgresRunLock(DataSource dataSource) { this.dataSource = dataSource; }

    public <T> LockResult<T> withLock(String database, String schema, Work<T> work) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            boolean acquired;
            try (PreparedStatement statement = connection.prepareStatement("SELECT pg_try_advisory_lock(hashtextextended(?, 0))")) {
                statement.setString(1, "tm-routine-validator:" + database + ":" + schema);
                try (ResultSet result = statement.executeQuery()) { result.next(); acquired = result.getBoolean(1); }
            }
            if (!acquired) return new LockResult<>(false, null);
            try { return new LockResult<>(true, work.run()); }
            finally {
                try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_unlock(hashtextextended(?, 0))")) {
                    statement.setString(1, "tm-routine-validator:" + database + ":" + schema);
                    statement.execute();
                } catch (SQLException ignored) { /* Session close also releases the lock. */ }
            }
        }
    }
    @FunctionalInterface public interface Work<T> { T run() throws Exception; }
    public record LockResult<T>(boolean acquired, T value) { }
}
