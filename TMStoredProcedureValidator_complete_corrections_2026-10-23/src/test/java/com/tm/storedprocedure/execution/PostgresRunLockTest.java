package com.tm.storedprocedure.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class PostgresRunLockTest {
    @Test
    void releasesTheSessionLockAfterSuccessfulWork() throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement acquire = mock(PreparedStatement.class);
        PreparedStatement release = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(contains("pg_try_advisory_lock"))).thenReturn(acquire);
        when(connection.prepareStatement(contains("pg_advisory_unlock"))).thenReturn(release);
        when(acquire.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getBoolean(1)).thenReturn(true);
        int[] workCalls = {0};

        PostgresRunLock.LockResult<Integer> lock = new PostgresRunLock(source).withLock("tm", "public", () -> ++workCalls[0]);

        assertThat(lock.acquired()).isTrue();
        assertThat(lock.value()).isEqualTo(1);
        verify(connection).setAutoCommit(true);
        verify(release).execute();
    }

    @Test
    void skipsWorkWhenAnotherInstanceOwnsTheLock() throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement acquire = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(contains("pg_try_advisory_lock"))).thenReturn(acquire);
        when(acquire.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getBoolean(1)).thenReturn(false);

        PostgresRunLock.LockResult<Integer> lock = new PostgresRunLock(source).withLock("tm", "public", () -> 1);

        assertThat(lock.acquired()).isFalse();
        assertThat(lock.value()).isNull();
        verify(acquire, never()).execute();
    }
}
