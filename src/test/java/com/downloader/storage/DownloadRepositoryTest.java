package com.downloader.storage;

import com.downloader.core.DownloadState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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

        List<Long> ids = repository.findResumable().stream()
                .map(DownloadRecord::id)
                .toList();

        assertEquals(List.of(queued, downloading, failed), ids);
    }

    @Test
    void dataSurvivesReopeningTheDatabase() throws Exception {
        long id = insert("a.bin", DownloadState.DOWNLOADING);
        repository.close();

        repository = new DownloadRepository(tempDir.resolve("test.db"));

        assertEquals(id, repository.findAll().get(0).id());
    }
}
