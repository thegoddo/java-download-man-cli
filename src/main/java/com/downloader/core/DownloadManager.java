package com.downloader.core;

import com.downloader.http.HttpDownloader;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class DownloadManager {

    private final HttpDownloader httpDownloader = new HttpDownloader();
    private final List<DownloadTask> tasks = Collections.synchronizedList(
        new ArrayList<>()
    );

    private final ExecutorService executorService =
        Executors.newFixedThreadPool(3);
    private final ExecutorCompletionService<String> completionService =
        new ExecutorCompletionService<>(executorService);

    // Asynchronous download
    public Future<String> downloadAsync(URI uri, Path path) {
        DownloadTask downloadTask = new DownloadTask(uri, path);
        tasks.add(downloadTask);

        Future<String> future = completionService.submit(() -> {
            downloadTask.transitionTo(DownloadState.DOWNLOADING);

            try {
                httpDownloader.download(uri, path);
                downloadTask.transitionTo(DownloadState.COMPLETED);
                return "Download Complete";
            } catch (InterruptedException e) {
                downloadTask.transitionTo(DownloadState.FAILED);
                Thread.currentThread().interrupt();
                return "Download failed: " + e.getMessage();
            } catch (IOException e) {
                downloadTask.transitionTo(DownloadState.FAILED);
                return "Download failed: " + e.getMessage();
            }
        });
        return future;
    }

    public Future<String> takeCompletedDownload() throws InterruptedException{
        return completionService.take();
    }

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
        synchronized (tasks) {
            return List.copyOf(tasks);
        }
    }

    public void printAllTasks() {
        System.out.println("Download History");
        System.out.println("----------------");

        for (DownloadTask task : getAllTasks()) {
            System.out.println("URL: " + task.getUri());
            System.out.println("Destination: " + task.getDestination());
            System.out.println("State: " + task.getState());
            System.out.println();
        }
    }

    public void shutdownAndAwaitTermination() {
        executorService.shutdown();

        try {
            if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
