package com.downloader.http;

import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class HttpDownloadDemo {
    public static void main(String[] args) throws Exception {

        HttpClient client = HttpClient.newHttpClient();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://www.w3.org/WAI/ER/tests/xhtml/testfiles/resources/pdf/dummy.pdf"))
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

        byte[] buffer = new byte[8192];
        int bytesRead;

        try (InputStream inputStream = response.body();
             OutputStream outputStream = new FileOutputStream("Download.pdf")) {

            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
        }

        System.out.println("Download completed successfully!");
    }
}
