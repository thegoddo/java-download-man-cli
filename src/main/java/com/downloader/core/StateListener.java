package com.downloader.core;

@FunctionalInterface
public interface StateListener {

    void stateChanged(DownloadTask task, DownloadState state);
}
