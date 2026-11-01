package com.downloader.http;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class RangeRequestDemo {
    public static void main(String[] args) throws Exception {

        HttpClient client = HttpClient.newHttpClient();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(
                    "https://www.w3.org/WAI/ER/tests/xhtml/testfiles/resources/pdf/dummy.pdf"
                ))
                .header("Range", "bytes=0-99")
                .GET()
                .build();

        HttpResponse<byte[]> response = client.send(
                request,
                HttpResponse.BodyHandlers.ofByteArray()
        );

        System.out.println("Status: " + response.statusCode());

        System.out.println(
                "Content-Range: " +
                response.headers()
                        .firstValue("Content-Range")
                        .orElse("Not provided")
        );

        System.out.println(
                "Bytes received: " + response.body().length
        );
    }
}
