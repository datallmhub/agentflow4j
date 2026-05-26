package io.github.datallmhub.agentflow4j.graph;

import java.io.EOFException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FailureClassifierTests {

    private final FailureClassifier classifier = FailureClassifier.defaults();

    // ----- JDK transient I/O ---------------------------------------------

    @Test
    void ioExceptionIsTransient() {
        FailureClassification c = classifier.classify(new IOException("boom"));
        assertThat(c.category()).isEqualTo(FailureCategory.TRANSIENT);
        assertThat(c.retryAfter()).isNull();
    }

    @Test
    void socketTimeoutIsTransientViaIOException() {
        FailureClassification c = classifier.classify(new SocketTimeoutException("read"));
        assertThat(c.category()).isEqualTo(FailureCategory.TRANSIENT);
    }

    @Test
    void timeoutExceptionIsTransient() {
        FailureClassification c = classifier.classify(new TimeoutException("slow"));
        assertThat(c.category()).isEqualTo(FailureCategory.TRANSIENT);
    }

    @Test
    void plainRuntimeExceptionIsDeclined() {
        // Unknown exception types are declined (null) so the policy's
        // retryOn predicate gets the final word — preserves backward compat
        // for callers that previously relied on `RetryPredicates.always()`.
        FailureClassification c = classifier.classify(new IllegalArgumentException("nope"));
        assertThat(c).isNull();
    }

    @Test
    void nullCauseIsPermanent() {
        FailureClassification c = classifier.classify(null);
        assertThat(c.category()).isEqualTo(FailureCategory.PERMANENT);
    }

    // ----- Cause chain walking -------------------------------------------

    @Test
    void unwrapsCompletionExceptionToFindTransientCause() {
        Throwable wrapped = new CompletionException(new EOFException("eof"));
        FailureClassification c = classifier.classify(wrapped);
        assertThat(c.category()).isEqualTo(FailureCategory.TRANSIENT);
    }

    @Test
    void unwrapsToOverBudgetEvenWhenWrapped() {
        Throwable wrapped = new RuntimeException("outer",
                new BudgetExceededException("monthly cap reached"));
        FailureClassification c = classifier.classify(wrapped);
        assertThat(c.category()).isEqualTo(FailureCategory.OVER_BUDGET);
        assertThat(c.reason()).isEqualTo("monthly cap reached");
    }

    // ----- Budget signal -------------------------------------------------

    @Test
    void budgetExceededExceptionIsOverBudget() {
        FailureClassification c = classifier.classify(
                new BudgetExceededException("monthly cap reached"));
        assertThat(c.category()).isEqualTo(FailureCategory.OVER_BUDGET);
        assertThat(c.reason()).isEqualTo("monthly cap reached");
    }

    @Test
    void budgetExceededWithoutMessageStillClassifies() {
        FailureClassification c = classifier.classify(new BudgetExceededException(null));
        assertThat(c.category()).isEqualTo(FailureCategory.OVER_BUDGET);
        assertThat(c.reason()).isEqualTo("budget exceeded");
    }

    // ----- Spring HTTP (via test stubs) ----------------------------------

    @Test
    void springTooManyRequestsWithoutRetryAfterIsTransient() {
        Throwable t = new org.springframework.web.client.HttpClientErrorException.TooManyRequests("429");
        FailureClassification c = classifier.classify(t);
        assertThat(c.category()).isEqualTo(FailureCategory.TRANSIENT);
        assertThat(c.retryAfter()).isNull();
    }

    @Test
    void springTooManyRequestsHonoursRetryAfterSeconds() {
        org.springframework.web.client.HttpClientErrorException.TooManyRequests t =
                new org.springframework.web.client.HttpClientErrorException.TooManyRequests("429");
        t.withHeader("Retry-After", "7");
        FailureClassification c = classifier.classify(t);
        assertThat(c.category()).isEqualTo(FailureCategory.TRANSIENT);
        assertThat(c.retryAfter()).isEqualTo(Duration.ofSeconds(7));
    }

    @Test
    void springTooManyRequestsWithUnparseableRetryAfterDoesNotCrash() {
        org.springframework.web.client.HttpClientErrorException.TooManyRequests t =
                new org.springframework.web.client.HttpClientErrorException.TooManyRequests("429");
        t.withHeader("Retry-After", "Fri, 31 Dec 2099 23:59:59 GMT"); // HTTP-date form, not parsed in MVP
        FailureClassification c = classifier.classify(t);
        assertThat(c.category()).isEqualTo(FailureCategory.TRANSIENT);
        assertThat(c.retryAfter()).isNull(); // falls back to policy backoff
    }

    @Test
    void springTooManyRequestsWithNegativeRetryAfterIgnoresIt() {
        org.springframework.web.client.HttpClientErrorException.TooManyRequests t =
                new org.springframework.web.client.HttpClientErrorException.TooManyRequests("429");
        t.withHeader("Retry-After", "-3");
        FailureClassification c = classifier.classify(t);
        assertThat(c.category()).isEqualTo(FailureCategory.TRANSIENT);
        assertThat(c.retryAfter()).isNull();
    }

    @Test
    void springServerErrorIsTransient() {
        Throwable t = new org.springframework.web.client.HttpServerErrorException("503");
        FailureClassification c = classifier.classify(t);
        assertThat(c.category()).isEqualTo(FailureCategory.TRANSIENT);
    }

    @Test
    void springClientErrorOtherThan429IsPermanent() {
        Throwable t = new org.springframework.web.client.HttpClientErrorException.BadRequest("400");
        FailureClassification c = classifier.classify(t);
        assertThat(c.category()).isEqualTo(FailureCategory.PERMANENT);
        assertThat(c.reason()).isEqualTo("HTTP 4xx (non-retryable)");
    }

    @Test
    void springResourceAccessExceptionIsTransient() {
        Throwable t = new org.springframework.web.client.ResourceAccessException("connect refused");
        FailureClassification c = classifier.classify(t);
        assertThat(c.category()).isEqualTo(FailureCategory.TRANSIENT);
    }

    // ----- Composition / extensibility -----------------------------------

    @Test
    void orElseDelegatesWhenFirstReturnsNull() {
        FailureClassifier custom = cause -> null;          // declines
        FailureClassifier chain  = custom.orElse(classifier);
        FailureClassification c = chain.classify(new IOException("x"));
        assertThat(c.category()).isEqualTo(FailureCategory.TRANSIENT);
    }

    @Test
    void orElseRespectsFirstClassifierDecision() {
        FailureClassifier custom = cause ->
                cause instanceof IllegalStateException
                        ? FailureClassification.permanent("domain rule")
                        : null;
        FailureClassifier chain = custom.orElse(classifier);

        FailureClassification override = chain.classify(new IllegalStateException("x"));
        assertThat(override.category()).isEqualTo(FailureCategory.PERMANENT);
        assertThat(override.reason()).isEqualTo("domain rule");

        FailureClassification fallback = chain.classify(new IOException("y"));
        assertThat(fallback.category()).isEqualTo(FailureCategory.TRANSIENT);
    }

    // ----- RetryPolicy.classify(...) integration -------------------------

    @Test
    void retryPolicyClassifyUsesClassifierFirst() {
        RetryPolicy p = RetryPolicy.exponential(3, Duration.ofMillis(10));
        FailureClassification c = p.classify(new IOException("x"));
        assertThat(c.category()).isEqualTo(FailureCategory.TRANSIENT);
    }

    @Test
    void retryPolicyClassifyFallsBackToRetryOnPredicateWhenClassifierDeclines() {
        // A classifier that declines everything → falls back to retryOn predicate
        FailureClassifier declining = cause -> null;
        RetryPolicy p = new RetryPolicy(3, Duration.ofMillis(10), Duration.ofMillis(100),
                2.0, 0.0, RetryPredicates.always(), declining);

        FailureClassification c = p.classify(new IllegalArgumentException("x"));
        assertThat(c.category()).isEqualTo(FailureCategory.TRANSIENT);
    }

    @Test
    void retryPolicyClassifyFallsBackToRetryOnFalseWhenClassifierDeclines() {
        FailureClassifier declining = cause -> null;
        RetryPolicy p = new RetryPolicy(3, Duration.ofMillis(10), Duration.ofMillis(100),
                2.0, 0.0, RetryPredicates.never(), declining);

        FailureClassification c = p.classify(new IllegalArgumentException("x"));
        assertThat(c.category()).isEqualTo(FailureCategory.PERMANENT);
    }

    @Test
    void retryPolicyWithClassifierReplacesIt() {
        RetryPolicy base = RetryPolicy.exponential(3, Duration.ofMillis(10));
        FailureClassifier custom = cause -> FailureClassification.permanent("custom");
        RetryPolicy swapped = base.withClassifier(custom);

        assertThat(swapped.classify(new IOException("x")).category())
                .isEqualTo(FailureCategory.PERMANENT);
    }
}
