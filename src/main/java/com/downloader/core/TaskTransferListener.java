package com.downloader.core;

import com.downloader.http.TransferListener;

final class TaskTransferListener implements TransferListener {

    private final DownloadTask task;

    TaskTransferListener(DownloadTask task) {
        this.task = task;
    }

    @Override
    public void onStart(long existingBytes, long totalBytes) {
        task.setTotalBytes(totalBytes);
        task.setDownloadedBytes(existingBytes);
    }

    @Override
    public void onProgress(long downloadedBytes) {
        task.setDownloadedBytes(downloadedBytes);
    }

    @Override
    public boolean isCancelled() {
        return task.isCancellationRequested();
    }
}
