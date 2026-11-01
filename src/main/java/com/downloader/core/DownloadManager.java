package com.downloader.core;

import com.downloader.http.HttpDownloader;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

public class DownloadManager {

    private final HttpDownloader httpDownloader = new HttpDownloader();

    public void download(URI uri, Path path)
        throws IOException, InterruptedException {
        DownloadTask downloadTask = new DownloadTask(uri, path);
        downloadTask.transitionTo(DownloadState.DOWNLOADING);
        try {
            httpDownloader.download(uri, path);
        } catch (InterruptedException e) {
            downloadTask.transitionTo(DownloadState.FAILED);
            Thread.currentThread().interrupt();
            throw e;
        } catch (IOException e) {
            downloadTask.transitionTo(DownloadState.FAILED);
            throw e;
        }

        downloadTask.transitionTo(DownloadState.COMPLETED);
    }
}
