package com.downloader.core;

import com.downloader.http.HttpDownloader;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class DownloadManager {

    private final HttpDownloader httpDownloader = new HttpDownloader();

    private final Map<Future<String>, DownloadTask> futureToTask =
            new ConcurrentHashMap<>();

    private final List<DownloadTask> tasks =
            Collections.synchronizedList(new ArrayList<>());

    private final ExecutorService executorService =
            Executors.newFixedThreadPool(3);

    private final ExecutorCompletionService<String> completionService =
            new ExecutorCompletionService<>(executorService);

    private final Map<DownloadTask, Future<String>> downloadFutures =
            new ConcurrentHashMap<>();

    // Asynchronous download
    public synchronized Future<String> downloadAsync(
            URI uri, Path path) {

        DownloadTask downloadTask = new DownloadTask(uri, path);
        tasks.add(downloadTask);

        Future<String> future = completionService.submit(() -> {

            if (!downloadTask.startDownload()) {
                return "Download Cancelled";
            }

            try {
                httpDownloader.download(downloadTask);

                if (downloadTask.markTransferFinished()) {
                    downloadTask.transitionTo(DownloadState.COMPLETED);
                    return "Download Complete";
                }

                downloadTask.transitionTo(DownloadState.CANCELLED);
                return "Download Cancelled";

            } catch (InterruptedException e) {

                if (downloadTask.isCancellationRequested()) {
                    downloadTask.transitionTo(DownloadState.CANCELLED);
                    return "Download Cancelled";
                }

                downloadTask.transitionTo(DownloadState.FAILED);
                Thread.currentThread().interrupt();

                return "Download failed: " + e.getMessage();

            } catch (IOException e) {

                if (downloadTask.isCancellationRequested()) {
                    downloadTask.transitionTo(DownloadState.CANCELLED);
                    return "Download Cancelled";
                }

                downloadTask.transitionTo(DownloadState.FAILED);
                return "Download failed: " + e.getMessage();
            }
        });

        downloadFutures.put(downloadTask, future);
        futureToTask.put(future, downloadTask);

        return future;
    }

    // Cancel a download
    public synchronized boolean cancelDownload(DownloadTask task) {

        if (task == null) {
            return false;
        }

        Future<String> future = downloadFutures.get(task);

        if (future == null || future.isDone()) {
            return false;
        }

        if (!task.requestCancellation()) {
            return false;
        }

        boolean cancelled = future.cancel(true);

        return cancelled || task.getState() == DownloadState.CANCELLED;
    }

    // Get next completed download and clean up mappings
    public Future<String> takeCompletedDownload()
            throws InterruptedException {

        Future<String> completedFuture = completionService.take();

        DownloadTask task = futureToTask.remove(completedFuture);

        if (task != null) {
            downloadFutures.remove(task);
        }

        return completedFuture;
    }

    // Synchronous download
    public void download(DownloadTask task)
            throws IOException, InterruptedException {

        task.transitionTo(DownloadState.DOWNLOADING);
        tasks.add(task);

        try {
            httpDownloader.download(task);

        } catch (InterruptedException e) {

            task.transitionTo(DownloadState.FAILED);
            Thread.currentThread().interrupt();
            throw e;

        } catch (IOException e) {

            task.transitionTo(DownloadState.FAILED);
            throw e;
        }

        task.transitionTo(DownloadState.COMPLETED);
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

    public void printDownloadProgress() {
        for (DownloadTask task : getAllTasks()) {
            if (task.getState() == DownloadState.DOWNLOADING) {
                System.out.printf(
                    "%s | %.2f%% | %d / %d bytes%n",
                    task.getDestination().getFileName(),
                    task.calculateProgress(),
                    task.getDownloadedBytes(),
                    task.getTotalBytes()
                );
            }
        }
    }
}
