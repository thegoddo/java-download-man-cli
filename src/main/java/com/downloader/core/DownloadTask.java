package com.downloader.core;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class DownloadTask {

    private volatile long id = -1;

    private final URI uri;
    private final Path destination;

    private volatile Consumer<DownloadTask> progressListener;
    private volatile StateListener stateListener;

    private volatile long downloadedBytes;
    private volatile long totalBytes = -1;

    private DownloadState state;

    private volatile boolean cancellationRequested = false;

    private boolean transferFinished = false;

    private static final Map<DownloadState, List<DownloadState>>
        ALLOWED_TRANSITIONS = Map.of(

            DownloadState.QUEUED,
            List.of(
                DownloadState.DOWNLOADING,
                DownloadState.CANCELLED
            ),

            DownloadState.DOWNLOADING,
            List.of(
                DownloadState.COMPLETED,
                DownloadState.FAILED,
                DownloadState.CANCELLED
            ),

            DownloadState.COMPLETED, List.of(),
            DownloadState.FAILED, List.of(),
            DownloadState.CANCELLED, List.of()
        );

    public DownloadTask(URI uri, Path destination) {

        if (uri == null || destination == null) {
            throw new IllegalArgumentException(
                "URI and destination cannot be null"
            );
        }

        this.uri = uri;
        this.destination = destination;
        this.state = DownloadState.QUEUED;
    }

    public static DownloadTask restore(
            long id, URI uri, Path destination,
            long downloadedBytes, long totalBytes) {

        DownloadTask task = new DownloadTask(uri, destination);
        task.id = id;
        task.downloadedBytes = downloadedBytes;
        task.totalBytes = totalBytes;
        return task;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public void setProgressListener(Consumer<DownloadTask> progressListener) {
        this.progressListener = progressListener;
    }

    public void setStateListener(StateListener stateListener) {
        this.stateListener = stateListener;
    }

    public synchronized DownloadState getState() {
        return state;
    }

    public URI getUri() {
        return uri;
    }

    public Path getDestination() {
        return destination;
    }

    public synchronized void transitionTo(DownloadState newState) {

        if (newState == null) {
            throw new IllegalArgumentException(
                "Download state cannot be null"
            );
        }

        if (!ALLOWED_TRANSITIONS.get(state).contains(newState)) {
            throw new IllegalStateException(
                "Invalid state transition: " + state + " -> " + newState
            );
        }

        changeState(newState);
    }

    public synchronized boolean startDownload() {

        if (state != DownloadState.QUEUED || cancellationRequested) {
            return false;
        }

        changeState(DownloadState.DOWNLOADING);
        return true;
    }

    public synchronized boolean requestCancellation() {

        if (transferFinished || state.isFinished()) {
            return false;
        }

        cancellationRequested = true;

        if (state == DownloadState.QUEUED) {
            changeState(DownloadState.CANCELLED);
        } else {
            notifyStateListener(DownloadState.CANCELLED);
        }

        return true;
    }

    public synchronized boolean markTransferFinished() {

        if (cancellationRequested || state != DownloadState.DOWNLOADING) {
            return false;
        }

        transferFinished = true;
        return true;
    }

    private void changeState(DownloadState newState) {
        state = newState;
        notifyStateListener(newState);
    }

    private void notifyStateListener(DownloadState reportedState) {
        StateListener listener = stateListener;
        if (listener != null) {
            listener.stateChanged(this, reportedState);
        }
    }

    public boolean isCancellationRequested() {
        return cancellationRequested;
    }
    public long getDownloadedBytes() {
        return downloadedBytes;
    }

    public void setDownloadedBytes(long downloadedBytes) {
        this.downloadedBytes = downloadedBytes;

        Consumer<DownloadTask> listener = progressListener;
        if (listener != null) {
            listener.accept(this);
        }
    }

    public long getTotalBytes() {
        return totalBytes;
    }

    public void setTotalBytes(long totalBytes) {
        this.totalBytes = totalBytes;
    }

    public double calculateProgress() {
        // Calculate percentage, handling unknown total size.
        if(totalBytes <= 0) {
            return -1;
        }
        return ((double) downloadedBytes / totalBytes) * 100;
    }

}
