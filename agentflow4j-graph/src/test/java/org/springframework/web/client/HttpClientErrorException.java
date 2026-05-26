package org.springframework.web.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Test stub mimicking Spring Web's {@code HttpClientErrorException}.
 * Only the surface that {@code DefaultFailureClassifier} reflects upon is provided.
 * <p>This file lives in the test source set so it never reaches production classpath.
 */
public class HttpClientErrorException extends RuntimeException {

    private final Map<String, List<String>> headers = new HashMap<>();

    public HttpClientErrorException(String message) {
        super(message);
    }

    public Map<String, List<String>> getResponseHeaders() {
        return headers;
    }

    public HttpClientErrorException withHeader(String name, String value) {
        headers.put(name, List.of(value));
        return this;
    }

    /** 429 — the only 4xx subtype the classifier treats as TRANSIENT. */
    public static class TooManyRequests extends HttpClientErrorException {
        public TooManyRequests(String message) {
            super(message);
        }
    }

    /** Representative non-retryable 4xx subtype. */
    public static class BadRequest extends HttpClientErrorException {
        public BadRequest(String message) {
            super(message);
        }
    }
}
