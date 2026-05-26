package org.springframework.web.client;

/**
 * Test stub mimicking Spring Web's {@code HttpServerErrorException} (5xx).
 * Lives only in the test source set.
 */
public class HttpServerErrorException extends RuntimeException {
    public HttpServerErrorException(String message) {
        super(message);
    }
}
