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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HttpDownloader {

    private final HttpClient client = HttpClient.newHttpClient();

    public void download(DownloadTask task)
            throws IOException, InterruptedException {

        Path destination = task.getDestination();
        Path tempFile = Path.of(destination.toString() + ".part");

        long existingBytes = Files.exists(tempFile)
                ? Files.size(tempFile)
                : 0;

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(task.getUri())
                .GET();

        // Request only the remaining bytes if a partial file exists.
        if (existingBytes > 0) {
            requestBuilder.header("Range", "bytes=" + existingBytes + "-");
        }

        HttpRequest request = requestBuilder.build();

        HttpResponse<InputStream> response = client.send(
                request,
                HttpResponse.BodyHandlers.ofInputStream()
        );

        int statusCode = response.statusCode();

        if (statusCode == 416) {
            response.body().close();
            throw new IOException("Requested byte range is not satisfiable.");
        }

        if (statusCode != 200 && statusCode != 206) {
            response.body().close();
            throw new IOException(
                    "Download failed. HTTP status: " + statusCode
            );
        }

        boolean append;

        if (statusCode == 206) {

            if (existingBytes == 0) {
                response.body().close();
                throw new IOException(
                        "Received partial content without requesting a range."
                );
            }

            String contentRange = response.headers()
                    .firstValue("Content-Range")
                    .orElseThrow(() -> new IOException(
                            "Missing Content-Range header."
                    ));

            Matcher matcher = Pattern.compile(
                    "bytes (\\d+)-(\\d+)/(\\d+|\\*)"
            ).matcher(contentRange);

            if (!matcher.matches()) {
                response.body().close();
                throw new IOException("Invalid Content-Range header.");
            }

            long rangeStart = Long.parseLong(matcher.group(1));

            if (rangeStart != existingBytes) {
                response.body().close();
                throw new IOException(
                        "Server returned an unexpected byte range."
                );
            }

            long totalBytes = matcher.group(3).equals("*")
                    ? -1
                    : Long.parseLong(matcher.group(3));

            if (totalBytes > 0) {
                task.setTotalBytes(totalBytes);
            } else {
                long remainingBytes = response.headers()
                        .firstValueAsLong("Content-Length")
                        .orElse(-1);

                task.setTotalBytes(
                        remainingBytes > 0
                                ? existingBytes + remainingBytes
                                : -1
                );
            }

            task.setDownloadedBytes(existingBytes);
            append = true;

        } else {
            task.setTotalBytes(
                    response.headers()
                            .firstValueAsLong("Content-Length")
                            .orElse(-1)
            );

            task.setDownloadedBytes(0);
            append = false;
        }

        try (InputStream inputStream = response.body();
             OutputStream outputStream = new FileOutputStream(
                     tempFile.toFile(), append
             )) {

            byte[] buffer = new byte[8192];
            int bytesRead;
            long downloadedBytes = task.getDownloadedBytes();

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
