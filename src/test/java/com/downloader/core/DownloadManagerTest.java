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
        URI uri = server.serveSlowly("/slow", CONTENT, 1_000, 60);
        Path destination = tempDir.resolve("slow.bin");

        manager.downloadAsync(uri, destination);
        DownloadTask task = manager.getAllTasks().get(0);

        waitUntil(() -> task.getDownloadedBytes() > 0);
        assertTrue(manager.cancelDownload(task));

        Future<String> completed = manager.takeCompletedDownload();
        assertTrue(completed.isCancelled());

        assertEquals(DownloadState.CANCELLED, onlyRecord().state());

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

    @Test
    void runtimeExceptionInWorkerEndsFailed() throws Exception {
        URI uri = URI.create("ftp://example.com/file.bin");

        manager.downloadAsync(uri, tempDir.resolve("file.bin"));
        String result = manager.takeCompletedDownload().get();

        assertTrue(result.startsWith("Download failed"), result);
        assertEquals(DownloadState.FAILED,
                manager.getAllTasks().get(0).getState());
        manager.shutdownAndAwaitTermination();
        assertEquals(DownloadState.FAILED, onlyRecord().state());
    }

    @Test
    void cancelAfterLastByteNeverLeavesFinishedFileCancelled()
            throws Exception {
        URI uri = server.serveSlowly("/slow", CONTENT, 10_000, 30);
        Path destination = tempDir.resolve("slow.bin");

        manager.downloadAsync(uri, destination);
        DownloadTask task = manager.getAllTasks().get(0);

        task.setProgressListener(t -> {
            if (t.getDownloadedBytes() == CONTENT.length) {
                t.requestCancellation();
            }
        });

        assertEquals("Download Cancelled",
                manager.takeCompletedDownload().get());
        assertEquals(DownloadState.CANCELLED, task.getState());
        assertTrue(Files.notExists(destination));
    }

    @Test
    void manyFastDownloadsLeaveNoStaleEntries() throws Exception {
        URI uri = server.serve("/tiny", TestHttpServer.content(10));
        int count = 30;

        for (int i = 0; i < count; i++) {
            manager.downloadAsync(uri, tempDir.resolve("tiny" + i + ".bin"));
        }
        for (int i = 0; i < count; i++) {
            assertEquals("Download Complete",
                    manager.takeCompletedDownload().get());
        }

        assertEquals(0, manager.activeDownloadCount());
    }

    @Test
    void resumingTwiceSubmitsEachDownloadOnce() throws Exception {
        URI uri = server.serveSlowly("/slow", CONTENT, 10_000, 30);
        Path destination = tempDir.resolve("slow.bin");
        repository.insert(uri, destination, DownloadState.DOWNLOADING);

        assertEquals(1, manager.resumeDownloads().size());
        assertEquals(0, manager.resumeDownloads().size());

        assertEquals("Download Complete",
                manager.takeCompletedDownload().get());
        assertEquals(1, server.rangeHeaders().size());
    }

    @Test
    void resumeSkipsFailedUnlessAsked() throws Exception {
        URI uri = server.serve("/file", CONTENT);
        Path destination = tempDir.resolve("file.bin");
        repository.insert(uri, destination, DownloadState.FAILED);

        assertEquals(List.of(), manager.resumeDownloads());
        assertEquals(1, manager.resumeDownloads(true).size());

        assertEquals("Download Complete",
                manager.takeCompletedDownload().get());
        assertEquals(DownloadState.COMPLETED, onlyRecord().state());
    }

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
