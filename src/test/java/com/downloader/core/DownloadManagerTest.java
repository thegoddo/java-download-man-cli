package com.downloader.core;

import com.downloader.storage.DownloadRecord;
import com.downloader.storage.DownloadRepository;
import com.downloader.testutil.TestHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// End-to-end tests: manager + HttpDownloader + SQLite + a local server.
@Timeout(10)
class DownloadManagerTest {

    private static final byte[] CONTENT = TestHttpServer.content(100_000);

    @TempDir
    Path tempDir;

    private TestHttpServer server;
    private DownloadRepository repository;
    private DownloadManager manager;

    @BeforeEach
    void setUp() throws Exception {
        server = new TestHttpServer();
        repository = new DownloadRepository(tempDir.resolve("test.db"));
        manager = new DownloadManager(repository);
    }

    @AfterEach
    void tearDown() throws Exception {
        // Close the server first so a still-running slow download ends.
        server.close();
        manager.shutdownAndAwaitTermination();
        repository.close();
    }

    private DownloadRecord onlyRecord() throws Exception {
        List<DownloadRecord> records = repository.findAll();
        assertEquals(1, records.size());
        return records.get(0);
    }

    @Test
    void asyncDownloadCompletesAndIsSaved() throws Exception {
        URI uri = server.serve("/file", CONTENT);
        Path destination = tempDir.resolve("file.bin");

        manager.downloadAsync(uri, destination);
        Future<String> completed = manager.takeCompletedDownload();

        assertEquals("Download Complete", completed.get());
        assertArrayEquals(CONTENT, Files.readAllBytes(destination));

        DownloadRecord record = onlyRecord();
        assertEquals(DownloadState.COMPLETED, record.state());
        assertEquals(CONTENT.length, record.downloadedBytes());
        assertEquals(CONTENT.length, record.totalBytes());
    }

    @Test
    void cancellingSlowDownloadEndsCancelled() throws Exception {
        // About 6 seconds in total if nobody cancels it.
        URI uri = server.serveSlowly("/slow", CONTENT, 1_000, 60);
        Path destination = tempDir.resolve("slow.bin");

        manager.downloadAsync(uri, destination);
        DownloadTask task = manager.getAllTasks().get(0);

        waitUntil(() -> task.getDownloadedBytes() > 0);
        assertTrue(manager.cancelDownload(task));

        Future<String> completed = manager.takeCompletedDownload();
        assertTrue(completed.isCancelled());

        // future.cancel() returns before the worker has finished;
        // wait for it so the final state has been written.
        manager.shutdownAndAwaitTermination();

        assertEquals(DownloadState.CANCELLED, task.getState());
        assertEquals(DownloadState.CANCELLED, onlyRecord().state());
        assertTrue(Files.notExists(destination));
    }

    @Test
    void resumeDownloadsFinishesInterruptedDownload() throws Exception {
        URI uri = server.serve("/file", CONTENT);
        Path destination = tempDir.resolve("file.bin");
        int alreadyHave = 30_000;

        // State left behind by a run that crashed mid-download.
        long id = repository.insert(uri, destination, DownloadState.DOWNLOADING);
        repository.updateProgress(id, alreadyHave, CONTENT.length);
        Files.write(Path.of(destination + ".part"),
                Arrays.copyOfRange(CONTENT, 0, alreadyHave));

        List<Future<String>> resumed = manager.resumeDownloads();
        assertEquals(1, resumed.size());

        assertEquals("Download Complete",
                manager.takeCompletedDownload().get());
        assertEquals(List.of("bytes=" + alreadyHave + "-"),
                server.rangeHeaders());
        assertArrayEquals(CONTENT, Files.readAllBytes(destination));

        DownloadRecord record = onlyRecord();
        assertEquals(id, record.id());
        assertEquals(DownloadState.COMPLETED, record.state());
        assertEquals(CONTENT.length, record.downloadedBytes());
    }

    // Polls a condition for up to 5 seconds.
    private static void waitUntil(java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Condition not met within 5 seconds");
            }
            Thread.sleep(10);
        }
    }
}
