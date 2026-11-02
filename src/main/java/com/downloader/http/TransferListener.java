package com.downloader.http;

public interface TransferListener {

    void onStart(long existingBytes, long totalBytes);

    void onProgress(long downloadedBytes);

    boolean isCancelled();
}
