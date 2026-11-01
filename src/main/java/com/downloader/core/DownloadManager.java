package com.downloader.core;

import com.downloader.http.HttpDownloader;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class DownloadManager {

    private final HttpDownloader httpDownloader = new HttpDownloader();
    private final List<DownloadTask> tasks = new ArrayList<>();

    public void download(URI uri, Path path)
        throws IOException, InterruptedException {
        DownloadTask downloadTask = new DownloadTask(uri, path);
        downloadTask.transitionTo(DownloadState.DOWNLOADING);
        tasks.add(downloadTask);
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

    public List<DownloadTask> getAllTasks() {
        return List.copyOf(tasks);
    }

    public void printAllTasks() {
        System.out.println("Download History");
        System.out.println("----------------");
        for(DownloadTask task: getAllTasks()) {
            System.out.println("URL: " + task.getUri());
            System.out.println("Destination: " + task.getDestination());
            System.out.println("State: " + task.getState());
            System.out.println();
        }
    }
}
