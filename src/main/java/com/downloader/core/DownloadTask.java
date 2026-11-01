package com.downloader.core;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * DownloadTask
 */
public class DownloadTask {

    private final URI uri;
    private final Path destination;
    private DownloadState state;
    private static final Map<DownloadState, List<DownloadState>> ALLOWED_TRANSITIONS = Map.of(
           DownloadState.QUEUED,
               List.of(DownloadState.DOWNLOADING, DownloadState.CANCELLED),

           DownloadState.DOWNLOADING,
               List.of(DownloadState.COMPLETED, DownloadState.FAILED, DownloadState.CANCELLED),

           DownloadState.COMPLETED, List.of(),
           DownloadState.FAILED, List.of(),
           DownloadState.CANCELLED, List.of()
       );

    public DownloadTask(URI uri, Path destination ) {
        this.uri = uri;
        this.destination = destination;
        this.state = DownloadState.QUEUED;
    }

    public synchronized DownloadState getState() {
        return this.state;
    }

    public URI getUri() {
        return uri;
    }

    public Path getDestination() {
        return destination;
    }
    public synchronized void  transitionTo(DownloadState state) {
        if (state == null) {
            throw new IllegalArgumentException("Download state cannot be null");
        }
        if (ALLOWED_TRANSITIONS.get(this.state).contains(state)) {
            this.state = state;
        } else {
            throw new IllegalStateException(
                "Invalid transition from " + this.state + " to " + state
            );
        }

    }

}
