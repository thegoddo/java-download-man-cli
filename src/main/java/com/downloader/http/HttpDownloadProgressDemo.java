package com.downloader.http;

import java.io.FileOutputStream;
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

public class HttpDownloadProgressDemo {

    public static void main(String[] args) throws Exception {
        HttpClient client = HttpClient.newHttpClient();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(
                URI.create(
                    "https://www.w3.org/WAI/ER/tests/xhtml/testfiles/resources/pdf/dummy.pdf"
                )
            )
            .GET()
            .build();

        HttpResponse<InputStream> response = client.send(
            request,
            HttpResponse.BodyHandlers.ofInputStream()
        );

        if (response.statusCode() != 200) {
            response.body().close();
            throw new RuntimeException(
                "Download failed. HTTP status: " + response.statusCode()
            );
        }

        long contentLength = response
            .headers()
            .firstValueAsLong("Content-Length")
            .orElse(-1);

        long downloadedBytes = 0;

        byte[] buffer = new byte[8192];
        int bytesRead;

        Path tempFile = Path.of("ProgressDownload.pdf.part");
        Path finalFile = Path.of("ProgressDownload.pdf");

        try (
            InputStream inputStream = response.body();
            OutputStream outputStream = new FileOutputStream(tempFile.toFile())
        ) {
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);

                downloadedBytes += bytesRead;

                if (contentLength > 0) {
                    double progress =
                        ((double) downloadedBytes / contentLength) * 100;

                    System.out.printf("\rProgress: %.2f%%", progress);
                } else {
                    System.out.printf(
                        "\rDownloaded: %d bytes",
                        downloadedBytes
                    );
                }
            }
        }

        System.out.println(Files.size(tempFile));

        try {
            Files.move(
                tempFile,
                finalFile,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(
                tempFile,
                finalFile,
                StandardCopyOption.REPLACE_EXISTING
            );
        }

        System.out.println("\nDownload completed!");
    }
}
