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

    // Save progress to the database at most once per second,
    // or sooner if this many bytes arrived since the last save.
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

    // Re-submit every unfinished download stored in the database.
    // HttpDownloader continues from the existing .part file.
    public synchronized List<Future<String>> resumeDownloads() {

        List<DownloadRecord> records;

        try {
            records = repository.findResumable();
        } catch (SQLException e) {
            System.err.println(
                    "Could not load resumable downloads: " + e.getMessage()
            );
            return List.of();
        }

        List<Future<String>> futures = new ArrayList<>();

        for (DownloadRecord record : records) {
            DownloadTask task = DownloadTask.restore(
                    record.id(),
                    record.uri(),
                    record.destination(),
                    record.downloadedBytes(),
                    record.totalBytes()
            );

            // The record may say DOWNLOADING or FAILED; it is queued again now.
            saveTask(task);
            futures.add(submit(task));
        }

        return futures;
    }

    private Future<String> submit(DownloadTask downloadTask) {

        tasks.add(downloadTask);
        saveProgressThrottled(downloadTask);

        Future<String> future = completionService.submit(() -> {
            try {
                return runDownload(downloadTask);
            } finally {
                // Final write: end state and last progress value.
                saveTask(downloadTask);
            }
        });

        downloadFutures.put(downloadTask, future);
        futureToTask.put(future, downloadTask);

        return future;
    }

    private String runDownload(DownloadTask downloadTask) {

        if (!downloadTask.startDownload()) {
            return "Download Cancelled";
        }

        saveTask(downloadTask);

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

        // A queued task goes straight to CANCELLED and never runs,
        // so save that state here.
        if (task.getState() == DownloadState.CANCELLED) {
            saveTask(task);
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

            } catch (IOException e) {

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

    // Prints every download stored in the database, including
    // downloads from previous runs.
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

    // Persistence helpers. Failures are logged, never thrown,
    // so a database problem does not stop a download.

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
            repository.updateProgress(
                    task.getId(),
                    task.getDownloadedBytes(),
                    task.getTotalBytes()
            );
            repository.updateState(task.getId(), task.getState());
        } catch (SQLException e) {
            System.err.println(
                    "Could not update download " + task.getId()
                            + ": " + e.getMessage()
            );
        }
    }

    // Saves progress while bytes arrive, but not on every chunk.
    private void saveProgressThrottled(DownloadTask task) {

        // Only the download thread calls the listener, so plain
        // local state is enough here.
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
