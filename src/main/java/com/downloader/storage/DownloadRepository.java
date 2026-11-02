package com.downloader.storage;

import com.downloader.core.DownloadState;

import java.net.URI;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

// Stores download records in a SQLite database.
// Uses a single connection; every method is synchronized so the
// worker threads never use the connection at the same time.
public class DownloadRepository implements AutoCloseable {

    private final Connection connection;

    public DownloadRepository(Path databaseFile) throws SQLException {

        connection = DriverManager.getConnection(
                "jdbc:sqlite:" + databaseFile.toAbsolutePath()
        );

        createSchema();
    }

    private void createSchema() throws SQLException {

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS downloads (
                        id               INTEGER PRIMARY KEY AUTOINCREMENT,
                        uri              TEXT    NOT NULL,
                        destination      TEXT    NOT NULL,
                        state            TEXT    NOT NULL,
                        downloaded_bytes INTEGER NOT NULL DEFAULT 0,
                        total_bytes      INTEGER NOT NULL DEFAULT -1,
                        created_at       TEXT    NOT NULL,
                        updated_at       TEXT    NOT NULL
                    )
                    """);
        }
    }

    // Inserts a new record and returns its generated id.
    public synchronized long insert(
            URI uri, Path destination, DownloadState state)
            throws SQLException {

        String now = Instant.now().toString();

        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO downloads "
                        + "(uri, destination, state, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS
        )) {
            statement.setString(1, uri.toString());
            statement.setString(2, destination.toString());
            statement.setString(3, state.name());
            statement.setString(4, now);
            statement.setString(5, now);
            statement.executeUpdate();

            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("No id generated for download.");
                }
                return keys.getLong(1);
            }
        }
    }

    public synchronized void updateState(long id, DownloadState state)
            throws SQLException {

        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE downloads SET state = ?, updated_at = ? WHERE id = ?"
        )) {
            statement.setString(1, state.name());
            statement.setString(2, Instant.now().toString());
            statement.setLong(3, id);
            statement.executeUpdate();
        }
    }

    public synchronized void updateProgress(
            long id, long downloadedBytes, long totalBytes)
            throws SQLException {

        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE downloads SET downloaded_bytes = ?, total_bytes = ?, "
                        + "updated_at = ? WHERE id = ?"
        )) {
            statement.setLong(1, downloadedBytes);
            statement.setLong(2, totalBytes);
            statement.setString(3, Instant.now().toString());
            statement.setLong(4, id);
            statement.executeUpdate();
        }
    }

    public synchronized List<DownloadRecord> findAll() throws SQLException {
        return query("SELECT * FROM downloads ORDER BY id");
    }

    // Downloads that did not finish: still queued, interrupted by a
    // crash (left in DOWNLOADING), or failed.
    public synchronized List<DownloadRecord> findResumable()
            throws SQLException {

        return query(
                "SELECT * FROM downloads "
                        + "WHERE state IN ('QUEUED', 'DOWNLOADING', 'FAILED') "
                        + "ORDER BY id"
        );
    }

    private List<DownloadRecord> query(String sql) throws SQLException {

        List<DownloadRecord> records = new ArrayList<>();

        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {

            while (rs.next()) {
                records.add(new DownloadRecord(
                        rs.getLong("id"),
                        URI.create(rs.getString("uri")),
                        Path.of(rs.getString("destination")),
                        DownloadState.valueOf(rs.getString("state")),
                        rs.getLong("downloaded_bytes"),
                        rs.getLong("total_bytes"),
                        rs.getString("created_at"),
                        rs.getString("updated_at")
                ));
            }
        }

        return records;
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }
}
