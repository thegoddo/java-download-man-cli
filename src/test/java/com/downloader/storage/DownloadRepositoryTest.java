package com.downloader.storage;

import com.downloader.core.DownloadState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloadRepositoryTest {

    @TempDir
    Path tempDir;

    private DownloadRepository repository;

    @BeforeEach
    void openRepository() throws Exception {
        repository = new DownloadRepository(tempDir.resolve("test.db"));
    }

    @AfterEach
    void closeRepository() throws Exception {
        repository.close();
    }

    private long insert(String name, DownloadState state) throws Exception {
        return repository.insert(
                URI.create("http://example.com/" + name),
                tempDir.resolve(name),
                state);
    }

    @Test
    void insertStoresANewRecord() throws Exception {
        long id = insert("a.bin", DownloadState.QUEUED);

        List<DownloadRecord> records = repository.findAll();
        assertEquals(1, records.size());

        DownloadRecord record = records.get(0);
        assertEquals(id, record.id());
        assertEquals(URI.create("http://example.com/a.bin"), record.uri());
        assertEquals(tempDir.resolve("a.bin"), record.destination());
        assertEquals(DownloadState.QUEUED, record.state());
        assertEquals(0, record.downloadedBytes());
        assertEquals(-1, record.totalBytes());
        assertNotNull(record.createdAt());
    }

    @Test
    void insertGeneratesDistinctIds() throws Exception {
        long first = insert("a.bin", DownloadState.QUEUED);
        long second = insert("b.bin", DownloadState.QUEUED);

        assertNotEquals(first, second);
    }

    @Test
    void updateStateAndProgressAreSaved() throws Exception {
        long id = insert("a.bin", DownloadState.QUEUED);

        repository.updateState(id, DownloadState.DOWNLOADING);
        repository.updateProgress(id, 500, 2000);

        DownloadRecord record = repository.findAll().get(0);
        assertEquals(DownloadState.DOWNLOADING, record.state());
        assertEquals(500, record.downloadedBytes());
        assertEquals(2000, record.totalBytes());
    }

    @Test
    void findAllReturnsRecordsInIdOrder() throws Exception {
        long first = insert("a.bin", DownloadState.COMPLETED);
        long second = insert("b.bin", DownloadState.QUEUED);

        List<Long> ids = repository.findAll().stream()
                .map(DownloadRecord::id)
                .toList();

        assertEquals(List.of(first, second), ids);
    }

    @Test
    void findResumableSkipsCompletedAndCancelled() throws Exception {
        long queued = insert("queued.bin", DownloadState.QUEUED);
        long downloading = insert("downloading.bin", DownloadState.DOWNLOADING);
        long failed = insert("failed.bin", DownloadState.FAILED);
        insert("completed.bin", DownloadState.COMPLETED);
        insert("cancelled.bin", DownloadState.CANCELLED);

        List<Long> withoutFailed = repository.findResumable(false).stream()
                .map(DownloadRecord::id)
                .toList();
        List<Long> withFailed = repository.findResumable(true).stream()
                .map(DownloadRecord::id)
                .toList();

        assertEquals(List.of(queued, downloading), withoutFailed);
        assertEquals(List.of(queued, downloading, failed), withFailed);
    }

    @Test
    void updateSavesStateAndProgressTogether() throws Exception {
        long id = insert("a.bin", DownloadState.QUEUED);

        repository.update(id, DownloadState.COMPLETED, 2000, 2000);

        DownloadRecord record = repository.findAll().get(0);
        assertEquals(DownloadState.COMPLETED, record.state());
        assertEquals(2000, record.downloadedBytes());
        assertEquals(2000, record.totalBytes());
    }

    @Test
    void onlyOneOfTwoConnectionsCanClaimARow() throws Exception {
        insert("a.bin", DownloadState.DOWNLOADING);

        try (DownloadRepository other =
                     new DownloadRepository(tempDir.resolve("test.db"))) {

            DownloadRecord mine = repository.findResumable(false).get(0);
            DownloadRecord theirs = other.findResumable(false).get(0);

            assertTrue(repository.claimForResume(mine));
            assertFalse(other.claimForResume(theirs));
        }

        assertEquals(DownloadState.QUEUED,
                repository.findAll().get(0).state());
    }

    @Test
    void rowsWithUnknownStateAreSkipped() throws Exception {
        long good = insert("good.bin", DownloadState.QUEUED);
        long bad = insert("bad.bin", DownloadState.QUEUED);

        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + tempDir.resolve("test.db").toAbsolutePath());
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "UPDATE downloads SET state = 'PAUSED' WHERE id = " + bad);
        }

        assertEquals(List.of(good), repository.findAll().stream()
                .map(DownloadRecord::id).toList());
        assertEquals(List.of(good), repository.findResumable(true).stream()
                .map(DownloadRecord::id).toList());
    }

    @Test
    void databaseUsesWalJournalMode() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + tempDir.resolve("test.db").toAbsolutePath());
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA journal_mode")) {

            assertTrue(rs.next());
            assertEquals("wal", rs.getString(1).toLowerCase());
        }
    }

    @Test
    void dataSurvivesReopeningTheDatabase() throws Exception {
        long id = insert("a.bin", DownloadState.DOWNLOADING);
        repository.close();

        repository = new DownloadRepository(tempDir.resolve("test.db"));

        assertEquals(id, repository.findAll().get(0).id());
    }
}
