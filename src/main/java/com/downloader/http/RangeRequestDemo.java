package com.downloader.http;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class RangeRequestDemo {
    public static void main(String[] args) throws Exception {

        HttpClient client = HttpClient.newHttpClient();
        int totalFileSize = 100000;
        int bytes = 8192;
        long downloadedBytes = 0;
        double downloadProgress = 0;
        int remainingSize = totalFileSize;
        while( remainingSize > 0) {
            downloadedBytes += bytes;
            downloadProgress = ((double) downloadedBytes / totalFileSize) * 100;
            int currentChunk = (int) Math.min(bytes, remainingSize);
            System.out.println("Downloaded: " + downloadedBytes + "/" + totalFileSize + " bytes | Progress: " + downloadProgress + "%");
            remainingSize -= currentChunk;
        }


        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(
                    "https://www.w3.org/WAI/ER/tests/xhtml/testfiles/resources/pdf/dummy.pdf"
                ))
                .header("Range", "bytes=1000-1999")
                .GET()
                .build();

        HttpResponse<byte[]> response = client.send(
                request,
                HttpResponse.BodyHandlers.ofByteArray()
        );

        if(response.statusCode() == 206) {
            boolean bytesReceived = response.body().length == 1000;
            if(bytesReceived) {
                System.out.println("Bytes did received 1000 bytes.");
            }
        } else if(response.statusCode() == 200) {
            System.out.println("Server received the full file.");
        }

    }
}
