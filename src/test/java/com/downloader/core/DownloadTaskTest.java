package com.downloader.core;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloadTaskTest {

    private final URI uri = URI.create("http://example.com/file.bin");
    private final Path destination = Path.of("file.bin");

    private DownloadTask newTask() {
        return new DownloadTask(uri, destination);
    }

    @Test
    void newTaskStartsQueued() {
        DownloadTask task = newTask();

        assertEquals(DownloadState.QUEUED, task.getState());
        assertEquals(-1, task.getId());
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new DownloadTask(null, destination));
        assertThrows(IllegalArgumentException.class,
                () -> newTask().transitionTo(null));
    }

    @Test
    void validTransitionsSucceed() {
        DownloadTask completed = newTask();
        completed.transitionTo(DownloadState.DOWNLOADING);
        completed.transitionTo(DownloadState.COMPLETED);
        assertEquals(DownloadState.COMPLETED, completed.getState());

        DownloadTask failed = newTask();
        failed.transitionTo(DownloadState.DOWNLOADING);
        failed.transitionTo(DownloadState.FAILED);
        assertEquals(DownloadState.FAILED, failed.getState());

        DownloadTask cancelled = newTask();
        cancelled.transitionTo(DownloadState.CANCELLED);
        assertEquals(DownloadState.CANCELLED, cancelled.getState());
    }

    @Test
    void invalidTransitionsThrow() {
        DownloadTask task = newTask();

        assertThrows(IllegalStateException.class,
                () -> task.transitionTo(DownloadState.COMPLETED));

        task.transitionTo(DownloadState.DOWNLOADING);
        task.transitionTo(DownloadState.COMPLETED);

        assertThrows(IllegalStateException.class,
                () -> task.transitionTo(DownloadState.DOWNLOADING));
        assertEquals(DownloadState.COMPLETED, task.getState());
    }

    @Test
    void startDownloadOnlyWorksFromQueued() {
        DownloadTask task = newTask();

        assertTrue(task.startDownload());
        assertEquals(DownloadState.DOWNLOADING, task.getState());
        assertFalse(task.startDownload());
    }

    @Test
    void cancellingQueuedTaskCancelsImmediately() {
        DownloadTask task = newTask();

        assertTrue(task.requestCancellation());
        assertEquals(DownloadState.CANCELLED, task.getState());
        assertTrue(task.isCancellationRequested());

        assertFalse(task.startDownload());
        assertFalse(task.requestCancellation());
    }

    @Test
    void cancellingRunningTaskOnlySetsTheFlag() {
        DownloadTask task = newTask();
        task.startDownload();

        assertTrue(task.requestCancellation());

        assertEquals(DownloadState.DOWNLOADING, task.getState());
        assertFalse(task.markTransferFinished());
    }

    @Test
    void cancellationIsRefusedAfterTransferFinished() {
        DownloadTask task = newTask();
        task.startDownload();

        assertTrue(task.markTransferFinished());
        assertFalse(task.requestCancellation());
    }

    @Test
    void everyStateChangeNotifiesTheStateListener() {
        List<DownloadState> reported = new ArrayList<>();

        DownloadTask completed = newTask();
        completed.setStateListener((t, state) -> reported.add(state));
        completed.startDownload();
        completed.markTransferFinished();
        completed.transitionTo(DownloadState.COMPLETED);

        assertEquals(List.of(DownloadState.DOWNLOADING,
                DownloadState.COMPLETED), reported);

        reported.clear();
        DownloadTask queued = newTask();
        queued.setStateListener((t, state) -> reported.add(t.getState()));
        queued.requestCancellation();

        assertEquals(List.of(DownloadState.CANCELLED), reported);
    }

    @Test
    void cancellingRunningTaskReportsCancelledBeforeStateChanges() {
        List<DownloadState> reported = new ArrayList<>();
        DownloadTask task = newTask();
        task.startDownload();
        task.setStateListener((t, state) -> reported.add(state));

        task.requestCancellation();

        assertEquals(List.of(DownloadState.CANCELLED), reported);
        assertEquals(DownloadState.DOWNLOADING, task.getState());
    }

    @Test
    void restoreStartsQueuedWithSavedProgress() {
        DownloadTask task = DownloadTask.restore(
                42, uri, destination, 100, 1000);

        assertEquals(DownloadState.QUEUED, task.getState());
        assertEquals(42, task.getId());
        assertEquals(100, task.getDownloadedBytes());
        assertEquals(1000, task.getTotalBytes());
        assertEquals(10.0, task.calculateProgress(), 0.0001);
    }
}
