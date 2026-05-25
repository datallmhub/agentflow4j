package io.github.datallmhub.agentflow4j.graph;

import java.io.IOException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;

import org.jspecify.annotations.Nullable;

/**
 * Default {@link FailureClassifier} — knows about common JDK transient
 * exceptions, Spring AI / Spring Web HTTP exceptions, and the framework's
 * own {@link BudgetExceededException}.
 *
 * <p>Spring exceptions are detected by class name so this module stays free
 * of any Spring dependency at compile time. The {@code Retry-After} header is
 * extracted from {@code HttpClientErrorException.TooManyRequests} via
 * reflection — best-effort, never throws.
 *
 * <p>Walks the cause chain so that wrapped exceptions (e.g.
 * {@code CompletionException → IOException}) are classified by their root
 * cause rather than the wrapper.
 */
final class DefaultFailureClassifier implements FailureClassifier {

    static final DefaultFailureClassifier INSTANCE = new DefaultFailureClassifier();

    private static final int MAX_CAUSE_CHAIN_DEPTH = 10;

    private DefaultFailureClassifier() {}

    /**
     * {@inheritDoc}
     *
     * <p>Returns {@code null} (declines) for exceptions the classifier does
     * not specifically recognise — this lets the policy's legacy
     * {@link RetryPolicy#retryOn()} predicate decide for unknown types, and
     * keeps callers that only set {@code retryOn} working unchanged.
     */
    @Override
    @Nullable
    public FailureClassification classify(Throwable cause) {
        if (cause == null) {
            return FailureClassification.permanent("no cause");
        }

        for (Throwable t = cause; t != null; t = nextCause(t)) {
            // 1. Explicit framework signal — wins everything.
            if (t instanceof BudgetExceededException) {
                String reason = t.getMessage() != null ? t.getMessage() : "budget exceeded";
                return FailureClassification.overBudget(reason);
            }

            // 2. Spring AI / Spring Web HTTP, by class name (no compile-time dep).
            String name = t.getClass().getName();
            if (isTooManyRequests(name)) {
                Duration retryAfter = readRetryAfter(t);
                return retryAfter != null
                        ? FailureClassification.transientFailure(retryAfter)
                        : FailureClassification.transientFailure();
            }
            if (isHttpServerError(name)) {
                return FailureClassification.transientFailure();
            }
            if (isHttpClientError(name)) {
                return FailureClassification.permanent("HTTP 4xx (non-retryable)");
            }
            if (isResourceAccessError(name)) {
                // Spring wraps low-level I/O failures here — treat as transient.
                return FailureClassification.transientFailure();
            }

            // 3. Plain JDK transient I/O.
            if (t instanceof IOException || t instanceof TimeoutException) {
                return FailureClassification.transientFailure();
            }
        }

        // Unknown — decline so the policy's retryOn predicate decides.
        return null;
    }

    private static boolean isTooManyRequests(String className) {
        return "org.springframework.web.client.HttpClientErrorException$TooManyRequests".equals(className);
    }

    private static boolean isHttpServerError(String className) {
        return className.startsWith("org.springframework.web.client.HttpServerErrorException");
    }

    private static boolean isHttpClientError(String className) {
        // Plain HttpClientErrorException or any of its concrete 4xx subclasses
        // (BadRequest, Unauthorized, Forbidden, NotFound, MethodNotAllowed, …)
        // EXCEPT TooManyRequests, which we already short-circuited above.
        return className.startsWith("org.springframework.web.client.HttpClientErrorException");
    }

    private static boolean isResourceAccessError(String className) {
        return "org.springframework.web.client.ResourceAccessException".equals(className);
    }

    /**
     * Reads {@code Retry-After} from a Spring HTTP exception via reflection.
     * Honours the integer-seconds form; HTTP-date form is not parsed in the
     * MVP — falling back to {@code null} means the policy's normal backoff
     * kicks in, which is a safe default.
     */
    @Nullable
    private static Duration readRetryAfter(Throwable t) {
        try {
            Method getHeaders = t.getClass().getMethod("getResponseHeaders");
            Object headers = getHeaders.invoke(t);
            if (headers == null) {
                return null;
            }
            Method get = headers.getClass().getMethod("get", Object.class);
            Object value = get.invoke(headers, "Retry-After");
            if (value instanceof List<?> list && !list.isEmpty()) {
                String first = String.valueOf(list.get(0));
                try {
                    long seconds = Long.parseLong(first.trim());
                    if (seconds < 0) {
                        return null;
                    }
                    return Duration.ofSeconds(seconds);
                }
                catch (NumberFormatException ignored) {
                    // HTTP-date form — not parsed in MVP, caller falls back to policy backoff.
                    return null;
                }
            }
        }
        catch (Throwable ignored) {
            // Reflection failure — the exception class doesn't expose headers
            // (or has a different shape). Best-effort: no retry-after hint.
        }
        return null;
    }

    /** Returns the next cause, guarding against self-referential chains. */
    @Nullable
    private static Throwable nextCause(Throwable t) {
        Throwable next = t.getCause();
        if (next == null || next == t) {
            return null;
        }
        // Bound the walk so a pathological chain can't loop forever.
        int depth = 0;
        for (Throwable c = t; c != null && depth < MAX_CAUSE_CHAIN_DEPTH; c = c.getCause(), depth++) {
            if (c == next) {
                return depth + 1 < MAX_CAUSE_CHAIN_DEPTH ? next : null;
            }
        }
        return next;
    }
}
