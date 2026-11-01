package com.downloader;

import java.net.URI;
import java.nio.file.Path;

import com.downloader.core.DownloadManager;

public class Main {
    public static void main(String[] args) {
        if(args.length < 2) {
            System.out.println("Usage: java Main <URL> <destination>");
            return;
        }

        URI uri = URI.create(args[0]);
        Path destination = Path.of(args[1]);
        DownloadManager downloadManager = new DownloadManager();
        downloadManager.download(uri, destination);
    }
}
