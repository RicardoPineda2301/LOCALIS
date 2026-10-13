package com.localis;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.localis.net.NetworkGuard;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class NetworkGuardTest {

    private final NetworkGuard guard = new NetworkGuard(List.of("localhost", "127.0.0.1", "::1"));

    @Test
    void allowsLoopback() {
        assertDoesNotThrow(() -> guard.check(URI.create("http://localhost:11434/")));
        assertDoesNotThrow(() -> guard.check(URI.create("http://127.0.0.1:11434/")));
        assertDoesNotThrow(() -> guard.check(URI.create("http://[::1]:11434/")));
    }

    @Test
    void blocksAnythingElse() {
        assertThrows(SecurityException.class, () -> guard.check(URI.create("https://api.openai.com/v1")));
        assertThrows(SecurityException.class, () -> guard.check(URI.create("http://192.168.1.10:11434")));
        assertThrows(SecurityException.class, () -> guard.check(URI.create("http://localhost.evil.com/")));
    }
}
