package com.downloader.http;

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
import java.util.ArrayList;
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

    private void download(URI uri, Path destination, TransferListener listener)
            throws IOException, InterruptedException {
        Path part = downloader.downloadToPart(uri, destination, listener);
        HttpDownloader.moveIntoPlace(part, destination);
    }

    private static class RecordingListener implements TransferListener {

        final List<Long> starts = new ArrayList<>();
        final List<Long> progress = new ArrayList<>();
        long totalBytes = -1;
        long downloadedBytes;
        long cancelAfterBytes = Long.MAX_VALUE;

        @Override
        public void onStart(long existingBytes, long totalBytes) {
            starts.add(existingBytes);
            this.totalBytes = totalBytes;
            this.downloadedBytes = existingBytes;
        }

        @Override
        public void onProgress(long downloadedBytes) {
            progress.add(downloadedBytes);
            this.downloadedBytes = downloadedBytes;
        }

        @Override
        public boolean isCancelled() {
            return downloadedBytes >= cancelAfterBytes;
        }
    }

    @Test
    void fullDownloadWritesExactBytes() throws Exception {
        URI uri = server.serve("/file", CONTENT);
        Path destination = tempDir.resolve("file.bin");
        RecordingListener listener = new RecordingListener();

        download(uri, destination, listener);

        assertArrayEquals(CONTENT, Files.readAllBytes(destination));
        assertFalse(Files.exists(partFile(destination)));
        assertEquals(CONTENT.length, listener.downloadedBytes);
        assertEquals(CONTENT.length, listener.totalBytes);
        assertEquals(List.of(), server.rangeHeaders().stream()
                .filter(h -> h != null).toList());
    }

    @Test
    void plainListenerSeesStartThenIncreasingProgress() throws Exception {
        URI uri = server.serve("/file", CONTENT);
        Path destination = tempDir.resolve("file.bin");
        RecordingListener listener = new RecordingListener();

        Path part = downloader.downloadToPart(uri, destination, listener);

        assertEquals(partFile(destination), part);
        assertArrayEquals(CONTENT, Files.readAllBytes(part));
        assertFalse(Files.exists(destination));
        assertEquals(List.of(0L), listener.starts);
        assertEquals(CONTENT.length, listener.totalBytes);
        assertFalse(listener.progress.isEmpty());
        for (int i = 1; i < listener.progress.size(); i++) {
            assertTrue(listener.progress.get(i) > listener.progress.get(i - 1));
        }
        assertEquals(CONTENT.length,
                listener.progress.get(listener.progress.size() - 1));
    }

    @Test
    void resumesFromExistingPartFile() throws Exception {
        URI uri = server.serve("/file", CONTENT);
        Path destination = tempDir.resolve("file.bin");
        int alreadyHave = 40_000;
        Files.write(partFile(destination),
                Arrays.copyOfRange(CONTENT, 0, alreadyHave));

        RecordingListener listener = new RecordingListener();
        download(uri, destination, listener);

        assertEquals(List.of("bytes=" + alreadyHave + "-"),
                server.rangeHeaders());
        assertArrayEquals(CONTENT, Files.readAllBytes(destination));
        assertFalse(Files.exists(partFile(destination)));
        assertEquals(List.of((long) alreadyHave), listener.starts);
        assertEquals(CONTENT.length, listener.downloadedBytes);
    }

    @Test
    void serverIgnoringRangeOverwritesPartFile() throws Exception {
        URI uri = server.serveIgnoringRange("/file", CONTENT);
        Path destination = tempDir.resolve("file.bin");
        Files.write(partFile(destination), new byte[] {9, 9, 9, 9, 9});

        download(uri, destination, new RecordingListener());

        assertEquals(List.of("bytes=5-"), server.rangeHeaders());
        assertArrayEquals(CONTENT, Files.readAllBytes(destination));
    }

    @Test
    void errorStatusThrowsIOException() {
        URI uri = server.serveStatus("/missing", 404);
        Path destination = tempDir.resolve("missing.bin");

        IOException e = assertThrows(IOException.class,
                () -> download(uri, destination, new RecordingListener()));

        assertTrue(e.getMessage().contains("404"), e.getMessage());
        assertFalse(Files.exists(destination));
    }

    @Test
    void followsRedirects() throws Exception {
        URI target = server.serve("/real", CONTENT);
        URI uri = server.serveRedirect("/moved", target);
        Path destination = tempDir.resolve("file.bin");

        download(uri, destination, new RecordingListener());

        assertArrayEquals(CONTENT, Files.readAllBytes(destination));
    }

    @Test
    void completePartFileFinishesOn416() throws Exception {
        URI uri = server.serve("/file", CONTENT);
        Path destination = tempDir.resolve("file.bin");
        Files.write(partFile(destination), CONTENT);

        RecordingListener listener = new RecordingListener();
        download(uri, destination, listener);

        assertEquals(List.of("bytes=" + CONTENT.length + "-"),
                server.rangeHeaders());
        assertArrayEquals(CONTENT, Files.readAllBytes(destination));
        assertFalse(Files.exists(partFile(destination)));
        assertEquals(CONTENT.length, listener.downloadedBytes);
        assertEquals(CONTENT.length, listener.totalBytes);
    }

    @Test
    void oversizedPartFileIsDeletedOn416() throws Exception {
        URI uri = server.serve("/file", CONTENT);
        Path destination = tempDir.resolve("file.bin");
        Files.write(partFile(destination), new byte[CONTENT.length + 10]);

        IOException e = assertThrows(IOException.class,
                () -> download(uri, destination, new RecordingListener()));

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
                () -> download(uri, destination, new RecordingListener()));

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
        RecordingListener listener = new RecordingListener();
        listener.cancelAfterBytes = 1;

        IOException e = assertThrows(IOException.class,
                () -> download(uri, destination, listener));

        assertTrue(e.getMessage().contains("cancelled"), e.getMessage());
        assertTrue(listener.downloadedBytes < CONTENT.length);
        assertFalse(Files.exists(destination));
    }
}
