package com.downloader.core;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

import com.downloader.http.HttpDownloader;

public class DownloadManager {
    private final HttpDownloader httpDownloader = new HttpDownloader();
    public void download(URI uri, Path path) throws IOException,InterruptedException {
        httpDownloader.download(uri, path);
    }
}
