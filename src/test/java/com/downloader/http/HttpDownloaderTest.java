package com.downloader.http;

import com.downloader.core.DownloadTask;
import com.downloader.testutil.TestHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
class HttpDownloaderTest {

    private static final byte[] CONTENT = TestHttpServer.content(100_000);

    @TempDir
    Path tempDir;

    private TestHttpServer server;
    private final HttpDownloader downloader = new HttpDownloader();

    @BeforeEach
    void startServer() throws IOException {
        server = new TestHttpServer();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    private Path partFile(Path destination) {
        return Path.of(destination + ".part");
    }

    @Test
    void fullDownloadWritesExactBytes() throws Exception {
        URI uri = server.serve("/file", CONTENT);
        Path destination = tempDir.resolve("file.bin");
        DownloadTask task = new DownloadTask(uri, destination);

        downloader.download(task);

        assertArrayEquals(CONTENT, Files.readAllBytes(destination));
        assertFalse(Files.exists(partFile(destination)));
        assertEquals(CONTENT.length, task.getDownloadedBytes());
        assertEquals(CONTENT.length, task.getTotalBytes());
        assertEquals(List.of(), server.rangeHeaders().stream()
                .filter(h -> h != null).toList());
    }

    @Test
    void resumesFromExistingPartFile() throws Exception {
        URI uri = server.serve("/file", CONTENT);
        Path destination = tempDir.resolve("file.bin");
        int alreadyHave = 40_000;
        Files.write(partFile(destination),
                Arrays.copyOfRange(CONTENT, 0, alreadyHave));

        DownloadTask task = new DownloadTask(uri, destination);
        downloader.download(task);

        assertEquals(List.of("bytes=" + alreadyHave + "-"),
                server.rangeHeaders());
        assertArrayEquals(CONTENT, Files.readAllBytes(destination));
        assertFalse(Files.exists(partFile(destination)));
        assertEquals(CONTENT.length, task.getDownloadedBytes());
    }

    @Test
    void serverIgnoringRangeOverwritesPartFile() throws Exception {
        URI uri = server.serveIgnoringRange("/file", CONTENT);
        Path destination = tempDir.resolve("file.bin");
        Files.write(partFile(destination), new byte[] {9, 9, 9, 9, 9});

        DownloadTask task = new DownloadTask(uri, destination);
        downloader.download(task);

        assertEquals(List.of("bytes=5-"), server.rangeHeaders());
        assertArrayEquals(CONTENT, Files.readAllBytes(destination));
    }

    @Test
    void errorStatusThrowsIOException() {
        URI uri = server.serveStatus("/missing", 404);
        Path destination = tempDir.resolve("missing.bin");
        DownloadTask task = new DownloadTask(uri, destination);

        IOException e = assertThrows(IOException.class,
                () -> downloader.download(task));

        assertTrue(e.getMessage().contains("404"), e.getMessage());
        assertFalse(Files.exists(destination));
    }

    @Test
    void followsRedirects() throws Exception {
        URI target = server.serve("/real", CONTENT);
        URI uri = server.serveRedirect("/moved", target);
        Path destination = tempDir.resolve("file.bin");

        downloader.download(new DownloadTask(uri, destination));

        assertArrayEquals(CONTENT, Files.readAllBytes(destination));
    }

    @Test
    void completePartFileFinishesOn416() throws Exception {
        URI uri = server.serve("/file", CONTENT);
        Path destination = tempDir.resolve("file.bin");
        Files.write(partFile(destination), CONTENT);

        DownloadTask task = new DownloadTask(uri, destination);
        downloader.download(task);

        assertEquals(List.of("bytes=" + CONTENT.length + "-"),
                server.rangeHeaders());
        assertArrayEquals(CONTENT, Files.readAllBytes(destination));
        assertFalse(Files.exists(partFile(destination)));
        assertEquals(CONTENT.length, task.getDownloadedBytes());
        assertEquals(CONTENT.length, task.getTotalBytes());
    }

    @Test
    void oversizedPartFileIsDeletedOn416() throws Exception {
        URI uri = server.serve("/file", CONTENT);
        Path destination = tempDir.resolve("file.bin");
        Files.write(partFile(destination), new byte[CONTENT.length + 10]);

        IOException e = assertThrows(IOException.class,
                () -> downloader.download(new DownloadTask(uri, destination)));

        assertTrue(e.getMessage().contains("416"), e.getMessage());
        assertFalse(Files.exists(partFile(destination)));
        assertFalse(Files.exists(destination));
    }

    @Test
    void missingDestinationFolderFailsBeforeAnyRequest() {
        URI uri = server.serve("/file", CONTENT);
        Path folder = tempDir.resolve("no-such-folder");
        Path destination = folder.resolve("file.bin");

        IOException e = assertThrows(IOException.class,
                () -> downloader.download(new DownloadTask(uri, destination)));

        assertTrue(e.getMessage().contains("does not exist"), e.getMessage());
        assertTrue(e.getMessage().contains("does not create folders"),
                e.getMessage());
        assertEquals(List.of(), server.rangeHeaders());
        assertFalse(Files.exists(folder));
    }

    @Test
    void stopsWhenCancellationIsRequestedWithoutInterrupt() {
        URI uri = server.serve("/file", CONTENT);
        Path destination = tempDir.resolve("file.bin");
        DownloadTask task = new DownloadTask(uri, destination);
        task.startDownload();

        task.setProgressListener(t -> {
            if (t.getDownloadedBytes() > 0) {
                t.requestCancellation();
            }
        });

        IOException e = assertThrows(IOException.class,
                () -> downloader.download(task));

        assertTrue(e.getMessage().contains("cancelled"), e.getMessage());
        assertTrue(task.getDownloadedBytes() < CONTENT.length);
        assertFalse(Files.exists(destination));
    }
}
