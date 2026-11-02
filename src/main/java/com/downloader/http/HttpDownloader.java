package com.downloader.http;

import com.downloader.core.DownloadTask;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class HttpDownloader {

    private final HttpClient client = HttpClient.newHttpClient();

    public void download(DownloadTask task)
            throws IOException, InterruptedException {

        HttpRequest request = HttpRequest.newBuilder()
                .uri(task.getUri())
                .GET()
                .build();

        HttpResponse<InputStream> response = client.send(
                request,
                HttpResponse.BodyHandlers.ofInputStream()
        );

        if (response.statusCode() != 200) {
            response.body().close();

            throw new IOException(
                    "Download failed. HTTP status: " + response.statusCode()
            );
        }

        Path destination = task.getDestination();
        Path tempFile = Path.of(destination.toString() + ".part");

        try (
                InputStream inputStream = response.body();
                OutputStream outputStream =
                        new FileOutputStream(tempFile.toFile())
        ) {

            long contentLength = response.headers()
                    .firstValueAsLong("Content-Length")
                    .orElse(-1);

            task.setTotalBytes(contentLength);

            byte[] buffer = new byte[8192];
            int bytesRead;
            long downloadedBytes = 0;

            while ((bytesRead = inputStream.read(buffer)) != -1) {

                outputStream.write(buffer, 0, bytesRead);

                downloadedBytes += bytesRead;

                task.setDownloadedBytes(downloadedBytes);
            }
        }

        try {
            Files.move(
                    tempFile,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );

        } catch (AtomicMoveNotSupportedException e) {

            Files.move(
                    tempFile,
                    destination,
                    StandardCopyOption.REPLACE_EXISTING
            );
        }
    }
}
