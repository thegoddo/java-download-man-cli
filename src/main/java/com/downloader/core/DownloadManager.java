package com.downloader.core;

import com.downloader.http.HttpDownloader;
import com.downloader.storage.DownloadRecord;
import com.downloader.storage.DownloadRepository;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.sql.SQLException;
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

    private static final long PROGRESS_SAVE_INTERVAL_NANOS =
            TimeUnit.SECONDS.toNanos(1);
    private static final long PROGRESS_SAVE_BYTES = 4 * 1024 * 1024;

    private final HttpDownloader httpDownloader = new HttpDownloader();

    private final DownloadRepository repository;

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

    public DownloadManager(DownloadRepository repository) {
        this.repository = repository;
    }

    // Asynchronous download
    public synchronized Future<String> downloadAsync(
            URI uri, Path path) {

        DownloadTask downloadTask = new DownloadTask(uri, path);
        saveNewTask(downloadTask);

        return submit(downloadTask);
    }

    public List<Future<String>> resumeDownloads() {
        return resumeDownloads(false);
    }

    public synchronized List<Future<String>> resumeDownloads(
            boolean includeFailed) {

        List<DownloadRecord> records;

        try {
            records = repository.findResumable(includeFailed);
        } catch (SQLException e) {
            System.err.println(
                    "Could not load resumable downloads: " + e.getMessage()
            );
            return List.of();
        }

        List<Future<String>> futures = new ArrayList<>();

        for (DownloadRecord record : records) {

            if (isActive(record.id())) {
                continue;
            }

            try {
                if (!repository.claimForResume(record)) {
                    continue;
                }
            } catch (SQLException e) {
                System.err.println(
                        "Could not claim download " + record.id()
                                + ": " + e.getMessage()
                );
                continue;
            }

            DownloadTask task = DownloadTask.restore(
                    record.id(),
                    record.uri(),
                    record.destination(),
                    record.downloadedBytes(),
                    record.totalBytes()
            );

            futures.add(submit(task));
        }

        return futures;
    }

    private boolean isActive(long id) {
        for (DownloadTask task : downloadFutures.keySet()) {
            if (task.getId() == id) {
                return true;
            }
        }
        return false;
    }

    int activeDownloadCount() {
        return downloadFutures.size();
    }

    private Future<String> submit(DownloadTask downloadTask) {

        tasks.add(downloadTask);
        saveProgressThrottled(downloadTask);

        Future<String> future = completionService.submit(() -> {
            try {
                return runDownload(downloadTask);
            } finally {
                saveTask(downloadTask);
            }
        });

        downloadFutures.put(downloadTask, future);
        futureToTask.put(future, downloadTask);

        return future;
    }

    private String runDownload(DownloadTask downloadTask) {

        synchronized (this) {
            if (!downloadTask.startDownload()) {
                return "Download Cancelled";
            }

            saveTask(downloadTask);
        }

        try {
            Path partFile = httpDownloader.downloadToPart(downloadTask);

            if (!downloadTask.markTransferFinished()) {
                downloadTask.transitionTo(DownloadState.CANCELLED);
                return "Download Cancelled";
            }

            HttpDownloader.moveIntoPlace(
                    partFile, downloadTask.getDestination());

            downloadTask.transitionTo(DownloadState.COMPLETED);
            return "Download Complete";

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

        } catch (RuntimeException e) {

            if (downloadTask.isCancellationRequested()) {
                downloadTask.transitionTo(DownloadState.CANCELLED);
                return "Download Cancelled";
            }

            downloadTask.transitionTo(DownloadState.FAILED);
            return "Download failed: " + e;
        }
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

        saveCancelled(task);

        boolean cancelled = future.cancel(true);

        return cancelled || task.getState() == DownloadState.CANCELLED;
    }

    // Get next completed download and clean up mappings
    public Future<String> takeCompletedDownload()
            throws InterruptedException {

        Future<String> completedFuture = completionService.take();

        synchronized (this) {
            DownloadTask task = futureToTask.remove(completedFuture);

            if (task != null) {
                downloadFutures.remove(task);
            }
        }

        return completedFuture;
    }

    // Synchronous download
    public void download(DownloadTask task)
            throws IOException, InterruptedException {

        task.transitionTo(DownloadState.DOWNLOADING);
        tasks.add(task);

        if (task.getId() < 0) {
            saveNewTask(task);
        } else {
            saveTask(task);
        }
        saveProgressThrottled(task);

        try {
            try {
                httpDownloader.download(task);

            } catch (InterruptedException e) {

                task.transitionTo(DownloadState.FAILED);
                Thread.currentThread().interrupt();
                throw e;

            } catch (IOException | RuntimeException e) {

                task.transitionTo(DownloadState.FAILED);
                throw e;
            }

            task.transitionTo(DownloadState.COMPLETED);

        } finally {
            saveTask(task);
        }
    }

    public List<DownloadTask> getAllTasks() {

        synchronized (tasks) {
            return List.copyOf(tasks);
        }
    }

    public void printAllTasks() {

        System.out.println("Download History");
        System.out.println("----------------");

        List<DownloadRecord> records;

        try {
            records = repository.findAll();
        } catch (SQLException e) {
            System.err.println("Could not load history: " + e.getMessage());
            return;
        }

        if (records.isEmpty()) {
            System.out.println("No downloads yet.");
            return;
        }

        for (DownloadRecord record : records) {
            System.out.println("ID: " + record.id());
            System.out.println("URL: " + record.uri());
            System.out.println("Destination: " + record.destination());
            System.out.println("State: " + record.state());
            System.out.println("Bytes: " + record.downloadedBytes()
                    + " / " + record.totalBytes());
            System.out.println("Created: " + record.createdAt());
            System.out.println("Updated: " + record.updatedAt());
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

    private void saveNewTask(DownloadTask task) {
        try {
            task.setId(repository.insert(
                    task.getUri(), task.getDestination(), task.getState()
            ));
        } catch (SQLException e) {
            System.err.println(
                    "Could not save download " + task.getUri()
                            + ": " + e.getMessage()
            );
        }
    }

    private void saveTask(DownloadTask task) {
        if (task.getId() < 0) {
            return;
        }

        try {
            repository.update(
                    task.getId(),
                    task.getState(),
                    task.getDownloadedBytes(),
                    task.getTotalBytes()
            );
        } catch (SQLException e) {
            System.err.println(
                    "Could not update download " + task.getId()
                            + ": " + e.getMessage()
            );
        }
    }

    private void saveCancelled(DownloadTask task) {
        if (task.getId() < 0) {
            return;
        }

        try {
            repository.updateState(task.getId(), DownloadState.CANCELLED);
        } catch (SQLException e) {
            System.err.println(
                    "Could not update download " + task.getId()
                            + ": " + e.getMessage()
            );
        }
    }

    private void saveProgressThrottled(DownloadTask task) {

        long[] lastSaveTime = {0};
        long[] lastSavedBytes = {-1};

        task.setProgressListener(t -> {
            if (t.getId() < 0) {
                return;
            }

            long now = System.nanoTime();
            long bytes = t.getDownloadedBytes();

            boolean firstSave = lastSavedBytes[0] < 0;
            boolean timeElapsed =
                    now - lastSaveTime[0] >= PROGRESS_SAVE_INTERVAL_NANOS;
            boolean enoughBytes =
                    bytes - lastSavedBytes[0] >= PROGRESS_SAVE_BYTES;

            if (!firstSave && !timeElapsed && !enoughBytes) {
                return;
            }

            lastSaveTime[0] = now;
            lastSavedBytes[0] = bytes;

            try {
                repository.updateProgress(t.getId(), bytes, t.getTotalBytes());
            } catch (SQLException e) {
                System.err.println(
                        "Could not save progress for download " + t.getId()
                                + ": " + e.getMessage()
                );
            }
        });
    }
}
