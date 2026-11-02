package com.downloader;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainTest {

    @Test
    void parseUrlAcceptsHttpAndHttps() {
        assertEquals(URI.create("http://example.com/a.bin"),
                Main.parseUrl("http://example.com/a.bin"));
        assertEquals(URI.create("https://example.com/a.bin"),
                Main.parseUrl("https://example.com/a.bin"));
    }

    @Test
    void parseUrlGivesFriendlyErrorForBadUrls() {
        for (String bad : new String[] {
                "http://exa mple.com/a.bin",
                "example.com/a.bin",
                "ftp://example.com/a.bin"
        }) {
            IllegalArgumentException e = assertThrows(
                    IllegalArgumentException.class,
                    () -> Main.parseUrl(bad));

            assertTrue(e.getMessage().startsWith("Invalid URL '" + bad + "'"),
                    e.getMessage());
        }
    }
}
