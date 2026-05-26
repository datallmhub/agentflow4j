package org.springframework.web.client;

/**
 * Test stub mimicking Spring Web's {@code ResourceAccessException} — Spring's
 * wrapper around low-level I/O failures. Lives only in the test source set.
 */
public class ResourceAccessException extends RuntimeException {
    public ResourceAccessException(String message) {
        super(message);
    }
}
