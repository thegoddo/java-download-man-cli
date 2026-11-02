package com.downloader.core;

public enum DownloadState {

	QUEUED,
	DOWNLOADING,
	COMPLETED,
	FAILED,
	CANCELLED;

	public boolean isFinished() {
		return this == COMPLETED || this == FAILED || this == CANCELLED;
	}
}
