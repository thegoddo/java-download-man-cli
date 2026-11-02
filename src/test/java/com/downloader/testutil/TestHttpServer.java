package com.downloader.testutil;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Small in-process HTTP server for tests. Each serveXxx method registers
// a path and returns the full URI to request.
public final class TestHttpServer implements AutoCloseable {

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    // Every Range header the server received (null when none was sent).
    private final List<String> rangeHeaders = new CopyOnWriteArrayList<>();

    public TestHttpServer() throws IOException {
        server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(executor);
        server.start();
    }

    // Deterministic, non-repeating-ish test content.
    public static byte[] content(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) (i * 31 + i / 256);
        }
        return data;
    }

    public List<String> rangeHeaders() {
        return rangeHeaders;
    }

    // Serves the content and honours "Range: bytes=N-" with 206.
    public URI serve(String path, byte[] content) {
        server.createContext(path, exchange -> {
            String range = recordRange(exchange);

            if (range != null && range.startsWith("bytes=")
                    && range.endsWith("-")) {
                int start = Integer.parseInt(range.substring(6, range.length() - 1));
                int length = content.length - start;

                exchange.getResponseHeaders().add("Content-Range",
                        "bytes " + start + "-" + (content.length - 1)
                                + "/" + content.length);
                exchange.sendResponseHeaders(206, length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(content, start, length);
                }
                return;
            }

            sendAll(exchange, content);
        });
        return uri(path);
    }

    // Always answers 200 with the whole file, whatever Range says.
    public URI serveIgnoringRange(String path, byte[] content) {
        server.createContext(path, exchange -> {
            recordRange(exchange);
            sendAll(exchange, content);
        });
        return uri(path);
    }

    // Answers with the given status and an empty body.
    public URI serveStatus(String path, int status) {
        server.createContext(path, exchange -> {
            recordRange(exchange);
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        return uri(path);
    }

    // Sends the content in small chunks with a pause between them.
    public URI serveSlowly(String path, byte[] content,
                           int chunkSize, long delayMillis) {
        server.createContext(path, exchange -> {
            recordRange(exchange);
            exchange.sendResponseHeaders(200, content.length);
            try (OutputStream out = exchange.getResponseBody()) {
                for (int off = 0; off < content.length; off += chunkSize) {
                    out.write(content, off,
                            Math.min(chunkSize, content.length - off));
                    out.flush();
                    Thread.sleep(delayMillis);
                }
            } catch (InterruptedException | IOException e) {
                // Client went away or the server is shutting down.
            }
        });
        return uri(path);
    }

    private String recordRange(HttpExchange exchange) {
        String range = exchange.getRequestHeaders().getFirst("Range");
        rangeHeaders.add(range);
        return range;
    }

    private static void sendAll(HttpExchange exchange, byte[] content)
            throws IOException {
        exchange.sendResponseHeaders(200, content.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(content);
        }
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:"
                + server.getAddress().getPort() + path);
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
