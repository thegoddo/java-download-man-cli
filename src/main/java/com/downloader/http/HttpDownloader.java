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

        if (existingBytes > 0) {
            requestBuilder.header("Range", "bytes=" + existingBytes + "-");
        }

        HttpRequest request = requestBuilder.build();

        HttpResponse<InputStream> response = client.send(
                request,
                HttpResponse.BodyHandlers.ofInputStream()
        );

        try (InputStream inputStream = response.body()) {

            int statusCode = response.statusCode();

            if (statusCode == 416) {
                throw new IOException(
                        "Requested byte range is not satisfiable."
                );
            }

            if (statusCode != 200 && statusCode != 206) {
                throw new IOException(
                        "Download failed. HTTP status: " + statusCode
                );
            }

            boolean append = statusCode == 206;
            long expectedResponseBytes = -1;
            long totalBytes = -1;

            if (append) {

                if (existingBytes == 0) {
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
                    throw new IOException("Invalid Content-Range header.");
                }

                long rangeStart = Long.parseLong(matcher.group(1));
                long rangeEnd = Long.parseLong(matcher.group(2));

                if (rangeStart != existingBytes) {
                    throw new IOException(
                            "Server returned an unexpected range start."
                    );
                }

                if (rangeEnd < rangeStart) {
                    throw new IOException(
                            "Invalid Content-Range: end is before start."
                    );
                }

                expectedResponseBytes = rangeEnd - rangeStart + 1;

                if (!matcher.group(3).equals("*")) {
                    totalBytes = Long.parseLong(matcher.group(3));

                    if (rangeEnd >= totalBytes) {
                        throw new IOException(
                                "Content-Range exceeds the total file size."
                        );
                    }
                }

                long contentLength = response.headers()
                        .firstValueAsLong("Content-Length")
                        .orElse(-1);

                if (contentLength >= 0
                        && contentLength != expectedResponseBytes) {
                    throw new IOException(
                            "Content-Length does not match Content-Range."
                    );
                }

                task.setTotalBytes(totalBytes);
                task.setDownloadedBytes(existingBytes);

            } else {

                // HTTP 200: start from the beginning.
                totalBytes = response.headers()
                        .firstValueAsLong("Content-Length")
                        .orElse(-1);

                expectedResponseBytes = totalBytes;

                task.setTotalBytes(totalBytes);
                task.setDownloadedBytes(0);
            }

            try (OutputStream outputStream = new FileOutputStream(
                    tempFile.toFile(), append
            )) {

                byte[] buffer = new byte[8192];
                int bytesRead;

                long responseBytes = 0;
                long downloadedBytes = task.getDownloadedBytes();

                while ((bytesRead = inputStream.read(buffer)) != -1) {

                    // Don't write bytes beyond the advertised range.
                    if (expectedResponseBytes >= 0
                            && responseBytes + bytesRead
                            > expectedResponseBytes) {
                        throw new IOException(
                                "Server sent more bytes than advertised."
                        );
                    }

                    outputStream.write(buffer, 0, bytesRead);

                    responseBytes += bytesRead;
                    downloadedBytes += bytesRead;

                    task.setDownloadedBytes(downloadedBytes);
                }

                if (expectedResponseBytes >= 0
                        && responseBytes != expectedResponseBytes) {
                    throw new IOException(
                            "Received fewer bytes than advertised. "
                                    + "Expected: " + expectedResponseBytes
                                    + ", received: " + responseBytes
                    );
                }
            }

            // Verify the complete file size when the server provides it.
            if (totalBytes > 0) {
                long actualFileSize = Files.size(tempFile);

                if (actualFileSize != totalBytes) {
                    throw new IOException(
                            "File size mismatch. Expected: " + totalBytes
                                    + ", actual: " + actualFileSize
                    );
                }
            }
        }

        // Move only after all validation succeeds.
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
