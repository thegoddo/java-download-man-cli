package com.downloader;

import com.downloader.core.DownloadManager;
import com.downloader.storage.DownloadRepository;
import java.net.URI;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Main {

    private static final Path DATABASE_FILE = Path.of("downloads.db");

    public static void main(String[] args) {
        boolean history = args.length == 1 && args[0].equals("history");
        boolean resume = args.length == 1 && args[0].equals("resume");

        if (!history && !resume
                && (args.length < 2 || args.length % 2 != 0)) {
            printUsage();
            return;
        }

        try (DownloadRepository repository =
                 new DownloadRepository(DATABASE_FILE)) {

            DownloadManager downloadManager = new DownloadManager(repository);

            if (history) {
                downloadManager.printAllTasks();
            } else {
                runDownloads(downloadManager, args, resume);
            }
        } catch (SQLException e) {
            System.err.println("Database error: " + e.getMessage());
        }
    }

    private static void printUsage() {
        System.out.println(
            "Usage: java Main <URL1> <destination1> [<URL2> <destination2> ...]\n"
                + "       java Main resume     (continue unfinished downloads)\n"
                + "       java Main history    (show all downloads)"
        );
    }

    private static void runDownloads(
        DownloadManager downloadManager, String[] args, boolean resume) {

        ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(
            downloadManager::printDownloadProgress,
            0,
            1,
            TimeUnit.SECONDS
        );

        try {
            int numberOfDownloads;

            if (resume) {
                // Re-submit unfinished downloads from the database
                List<Future<String>> resumed =
                    downloadManager.resumeDownloads();

                numberOfDownloads = resumed.size();
                System.out.println("Resuming " + numberOfDownloads
                    + " download(s).");
            } else {
                // Submit downloads
                for (int i = 0; i < args.length; i += 2) {
                    URI uri = URI.create(args[i]);
                    Path destination = Path.of(args[i + 1]);

                    downloadManager.downloadAsync(uri, destination);

                    System.out.println("Submitted: " + uri);
                }

                numberOfDownloads = args.length / 2;
            }

            // Collect results in completion order
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
