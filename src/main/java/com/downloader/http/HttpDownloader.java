package com.downloader.http;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
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

    private static final Pattern UNSATISFIED_RANGE =
            Pattern.compile("bytes \\*/(\\d+)");

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public Path downloadToPart(
            URI uri, Path destination, TransferListener listener)
            throws IOException, InterruptedException {

        Path tempFile = Path.of(destination.toString() + ".part");

        Path folder = destination.toAbsolutePath().getParent();
        if (folder != null && !Files.isDirectory(folder)) {
            throw new IOException(
                    "Destination folder '" + folder + "' does not exist. "
                            + "Please create it first \u2014 this download "
                            + "manager intentionally does not create folders."
            );
        }

        long existingBytes = Files.exists(tempFile)
                ? Files.size(tempFile)
                : 0;

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(uri)
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
                long totalBytes = parseUnsatisfiedRangeTotal(response);

                if (totalBytes >= 0 && existingBytes == totalBytes) {
                    listener.onStart(totalBytes, totalBytes);
                    return tempFile;
                }

                Files.deleteIfExists(tempFile);
                throw new IOException(
                        "Server rejected the resume range (HTTP 416) and "
                                + "the partial file did not match the "
                                + "server's file size, so it was deleted. "
                                + "Run the download again to start over."
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

                listener.onStart(existingBytes, totalBytes);

            } else {

                // HTTP 200: start from the beginning.
                totalBytes = response.headers()
                        .firstValueAsLong("Content-Length")
                        .orElse(-1);

                expectedResponseBytes = totalBytes;

                listener.onStart(0, totalBytes);
            }

            try (OutputStream outputStream = new FileOutputStream(
                    tempFile.toFile(), append
            )) {

                byte[] buffer = new byte[8192];
                int bytesRead;

                long responseBytes = 0;
                long downloadedBytes = append ? existingBytes : 0;

                while ((bytesRead = inputStream.read(buffer)) != -1) {

                    if (listener.isCancelled()) {
                        throw new IOException("Download cancelled.");
                    }

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

                    listener.onProgress(downloadedBytes);
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

        return tempFile;
    }

    public static void moveIntoPlace(Path tempFile, Path destination)
            throws IOException {

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

    private static long parseUnsatisfiedRangeTotal(
            HttpResponse<InputStream> response) {

        return response.headers()
                .firstValue("Content-Range")
                .map(UNSATISFIED_RANGE::matcher)
                .filter(Matcher::matches)
                .map(m -> Long.parseLong(m.group(1)))
                .orElse(-1L);
    }
}
