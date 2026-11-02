package com.downloader;

import com.downloader.core.DownloadManager;
import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Main {

    public static void main(String[] args) {
        if (args.length < 2 || args.length % 2 != 0) {
            System.out.println(
                "Usage: java Main <URL1> <destination1> [<URL2> <destination2> ...]"
            );
            return;
        }

        DownloadManager downloadManager = new DownloadManager();
        ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(
            downloadManager::printDownloadProgress,
            0,
            1,
            TimeUnit.SECONDS
        );

        try {
            // Submit downloads
            for (int i = 0; i < args.length; i += 2) {
                URI uri = URI.create(args[i]);
                Path destination = Path.of(args[i + 1]);

                downloadManager.downloadAsync(uri, destination);

                System.out.println("Submitted: " + uri);
            }

            // Collect results in completion order
            int numberOfDownloads = args.length / 2;

            for (int i = 0; i < numberOfDownloads; i++) {
                try {
                    Future<String> completed =
                        downloadManager.takeCompletedDownload();

                    System.out.println(completed.get());
                } catch (CancellationException e) {
                    System.out.println("Download was cancelled.");
                } catch (ExecutionException e) {
                    System.err.println("Download task failed: " + e.getCause());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Main thread interrupted.");
        } finally {
            downloadManager.shutdownAndAwaitTermination();
            downloadManager.printAllTasks();
        }
        scheduler.shutdown();
    }
}
