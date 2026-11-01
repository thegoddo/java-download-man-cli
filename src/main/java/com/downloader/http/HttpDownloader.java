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

public class HttpDownloader {

    public void download(URI uri, Path destination)
        throws IOException, InterruptedException {
        HttpClient client = HttpClient.newHttpClient();

        HttpRequest request = HttpRequest.newBuilder().uri(uri).GET().build();

        HttpResponse<InputStream> response = client.send(
            request,
            HttpResponse.BodyHandlers.ofInputStream()
        );

        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException(
                "Download faile. HTTP status: " + response.statusCode()
            );
        }
        Path tempFile = Path.of(destination.toString() + ".part");

        try (
            InputStream inputStream = response.body();
            OutputStream outputStream = new FileOutputStream(tempFile.toFile());
        ) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            long contentLength = response
                .headers()
                .firstValueAsLong("Content-Length")
                .orElse(-1);
            long tempLength = contentLength;
            double downloadProgress = 0;
            long downloadedBytes = 0;

            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
                tempLength -= bytesRead;
                downloadedBytes += bytesRead;
                downloadProgress = ((double) downloadedBytes / contentLength) * 100;
            }
        }

        try {
            Files.move(tempFile, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tempFile, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
