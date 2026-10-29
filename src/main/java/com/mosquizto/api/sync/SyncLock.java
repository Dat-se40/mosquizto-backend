package com.mosquizto.api.sync;

import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/** Session lock on a dedicated connection; released even if the process crashes. */
@Component
public class SyncLock {
    private static final long LOCK_ID = 54820261029L;
    private final DataSource dataSource;

    public SyncLock(DataSource dataSource) { this.dataSource = dataSource; }

    public Handle acquire() {
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            try (var statement = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                statement.setLong(1, LOCK_ID);
                try (var result = statement.executeQuery()) {
                    if (!result.next() || !result.getBoolean(1)) {
                        throw new IllegalStateException("Another sync is already running");
                    }
                }
            }
            return new Handle(connection);
        } catch (SQLException | RuntimeException e) {
            if (connection != null) {
                try { connection.close(); } catch (SQLException ignored) {}
            }
            throw new IllegalStateException("Could not acquire the PostgreSQL sync lock", e);
        }
    }

    public static final class Handle implements AutoCloseable {
        private final Connection connection;
        private Handle(Connection connection) { this.connection = connection; }
        @Override
        public void close() {
            try (var statement = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                statement.setLong(1, LOCK_ID);
                statement.execute();
            } catch (SQLException e) {
                // Never return a possibly still-locked session to a connection pool.
                try { connection.abort(Runnable::run); } catch (SQLException ignored) {}
                throw new IllegalStateException("Could not release sync lock", e);
            } finally {
                try { connection.close(); } catch (SQLException ignored) {}
            }
        }
    }
}
