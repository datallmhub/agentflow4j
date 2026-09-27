package io.github.datallmhub.agentflow4j.openhands;

import java.time.Duration;

import io.github.datallmhub.agentflow4j.graph.FailureClassification;
import io.github.datallmhub.agentflow4j.graph.FailureClassifier;
import org.jspecify.annotations.Nullable;

/**
 * Sorts OpenHands API failures so a node retries what is worth retrying:
 * {@code 429} and {@code 5xx} are transient, other {@code 4xx} are permanent,
 * and a transport failure is transient. Anything else is declined, so a
 * chained classifier decides.
 *
 * <pre>{@code
 * RetryPolicy policy = RetryPolicy.exponential(3, Duration.ofSeconds(2))
 *         .withClassifier(OpenHandsFailureClassifier.INSTANCE.orElse(FailureClassifier.defaults()));
 * }</pre>
 */
public final class OpenHandsFailureClassifier implements FailureClassifier {

    public static final OpenHandsFailureClassifier INSTANCE = new OpenHandsFailureClassifier();

    private OpenHandsFailureClassifier() {}

    @Override
    @Nullable
    public FailureClassification classify(Throwable cause) {
        for (Throwable t = cause; t != null && t != t.getCause(); t = t.getCause()) {
            if (t instanceof OpenHandsException ex) {
                return classify(ex);
            }
        }
        return null;
    }

    private static FailureClassification classify(OpenHandsException ex) {
        int status = ex.statusCode();
        if (status == 0) {
            // No response: a network or timeout failure, worth another attempt.
            return FailureClassification.transientFailure();
        }
        if (status == 429) {
            return FailureClassification.transientFailure(Duration.ofSeconds(30));
        }
        if (status >= 500) {
            return FailureClassification.transientFailure();
        }
        if (status == 402 || status == 403) {
            return FailureClassification.overBudget("OpenHands refused the call: HTTP " + status);
        }
        return FailureClassification.permanent("OpenHands returned HTTP " + status);
    }
}
