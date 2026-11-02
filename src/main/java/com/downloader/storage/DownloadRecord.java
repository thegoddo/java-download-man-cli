package com.downloader.storage;

import com.downloader.core.DownloadState;

import java.net.URI;
import java.nio.file.Path;

public record DownloadRecord(
        long id,
        URI uri,
        Path destination,
        DownloadState state,
        long downloadedBytes,
        long totalBytes,
        String createdAt,
        String updatedAt
) {
}
