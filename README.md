# Java Download Manager

A command-line download manager written in Java. It downloads multiple files concurrently over HTTP, shows live progress, supports cancellation, and resumes interrupted downloads from partial `.part` files using HTTP range requests. Download history is stored in a local SQLite database, so unfinished downloads can be resumed after a restart.
