package com.downloader;

import com.downloader.core.DownloadManager;
import com.downloader.storage.DownloadRepository;
import java.net.URI;
import java.net.URISyntaxException;
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
        boolean resumeFailed = args.length == 2 && args[0].equals("resume")
                && args[1].equals("--failed");
        boolean resume = (args.length == 1 && args[0].equals("resume"))
                || resumeFailed;

        if (!history && !resume
                && (args.length < 2 || args.length % 2 != 0)) {
            printUsage();
            return;
        }

        if (!history && !resume) {
            for (int i = 0; i < args.length; i += 2) {
                try {
                    parseUrl(args[i]);
                } catch (IllegalArgumentException e) {
                    System.err.println(e.getMessage());
                    return;
                }
            }
        }

        try (DownloadRepository repository =
                 new DownloadRepository(DATABASE_FILE)) {

            DownloadManager downloadManager = new DownloadManager(repository);

            if (history) {
                downloadManager.printAllTasks();
            } else {
                runDownloads(downloadManager, args, resume, resumeFailed);
            }
        } catch (SQLException e) {
            System.err.println("Database error: " + e.getMessage());
        }
    }

    private static void printUsage() {
        System.out.println(
            "Usage: java Main <URL1> <destination1> [<URL2> <destination2> ...]\n"
                + "       java Main resume           (continue queued or interrupted downloads)\n"
                + "       java Main resume --failed  (same, and also retry failed downloads)\n"
                + "       java Main history          (show all downloads)\n"
                + "Destination folders must already exist; they are never created."
        );
    }

    static URI parseUrl(String text) {
        URI uri;

        try {
            uri = new URI(text);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(
                "Invalid URL '" + text + "': " + e.getReason()
            );
        }

        String scheme = uri.getScheme();
        boolean http = "http".equalsIgnoreCase(scheme)
            || "https".equalsIgnoreCase(scheme);

        if (!http || uri.getHost() == null) {
            throw new IllegalArgumentException(
                "Invalid URL '" + text
                    + "': expected an absolute http:// or https:// URL."
            );
        }

        return uri;
    }

    private static void runDownloads(
        DownloadManager downloadManager, String[] args,
        boolean resume, boolean resumeFailed) {

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
                List<Future<String>> resumed =
                    downloadManager.resumeDownloads(resumeFailed);

                numberOfDownloads = resumed.size();
                System.out.println("Resuming " + numberOfDownloads
                    + " download(s).");
            } else {
                // Submit downloads
                for (int i = 0; i < args.length; i += 2) {
                    URI uri = parseUrl(args[i]);
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
            try {
                downloadManager.shutdownAndAwaitTermination();
                downloadManager.printAllTasks();
            } finally {
                scheduler.shutdown();
            }
        }
    }
}
