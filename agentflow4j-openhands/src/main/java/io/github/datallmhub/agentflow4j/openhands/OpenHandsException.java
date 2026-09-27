package io.github.datallmhub.agentflow4j.openhands;

import org.jspecify.annotations.Nullable;

/** An OpenHands API call that did not succeed. */
public class OpenHandsException extends RuntimeException {

    private final int statusCode;
    @Nullable
    private final String body;

    public OpenHandsException(String message, int statusCode, @Nullable String body) {
        super(message + (body == null || body.isBlank() ? "" : ": " + body));
        this.statusCode = statusCode;
        this.body = body;
    }

    public OpenHandsException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
        this.body = null;
    }

    /** The HTTP status, or {@code 0} when the call never got a response. */
    public int statusCode() {
        return statusCode;
    }

    @Nullable
    public String body() {
        return body;
    }
}
