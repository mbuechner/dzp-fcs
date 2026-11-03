/*
 * Copyright 2023-2026 Michael Büchner, Deutsche Digitale Bibliothek
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package de.ddb.labs.dzpfcs.backend;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ProtocolException;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLProtocolException;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Reads backend responses with a shared, fair concurrency gate and bounded retries.
 * Instances are thread-safe and should be shared by requests for the same backend;
 * each invocation keeps its own attempt state and monotonic time budget.
 *
 * <p>The budget includes semaphore waiting, all attempts, complete response-body
 * reads and retry backoff. {@code maxAttempts} counts logical OkHttp calls. The
 * supplied client's native connection recovery may perform additional exchanges
 * within a call, but those exchanges share its remaining overall time budget.
 * Structured events measure logical calls and aggregate queue, active-call
 * and backoff time without recording URLs, search text or exception messages.
 *
 * <p>The supplied client is borrowed: its owner manages dispatcher shutdown and
 * connection-pool cleanup. Cancellation budgets do not guarantee wall-clock
 * latency when an interceptor, resolver, response close or synchronous logger
 * does not cooperate. Nor does canceling a client call prove that remote search
 * work has already stopped.</p>
 */
public final class BackendRequestExecutor {

    private static final Logger LOGGER = LogManager.getLogger(BackendRequestExecutor.class);

    private final OkHttpClient client;
    private final BackendHttpSettings settings;
    private final Semaphore permits;
    private final long requestBudgetNanos;

    /**
     * Creates a concurrency gate for all invocations on this executor. The client
     * retains its configured connect/read timeouts and native recovery policy;
     * the caller applies those connection settings when building the client.
     * This executor applies only the shared budget, gate and application retries.
     */
    public BackendRequestExecutor(OkHttpClient client, BackendHttpSettings settings) {
        this.client = Objects.requireNonNull(client, "client");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.permits = new Semaphore(settings.maxConcurrentRequests(), true);
        this.requestBudgetNanos = settings.requestTimeout().toNanos();
    }

    /**
     * Buffers the complete successful response body before returning it. JSON
     * interpretation belongs to the caller, so invalid payloads cannot trigger a
     * transport retry. Only GET requests are eligible for application retries.
     * Every response is closed before releasing its permit or retrying.
     * Retry backoff does not hold a concurrency permit; the next attempt queues
     * again fairly and shares the original deadline.
     *
     * @throws IOException if the HTTP status or I/O failure is not retryable, the
     *         attempt limit is reached, or a required wait cannot fit the budget
     * @throws InterruptedIOException if the budget expires or the thread is
     *         interrupted; an actual thread interruption preserves its flag
     */
    public String execute(Request request) throws IOException {
        final BackendTelemetry telemetry = new BackendTelemetry(LOGGER, requestBudgetNanos, settings.maxAttempts());
        Throwable terminalFailure = null;
        try {
            return execute(request, telemetry);
        } catch (IOException | RuntimeException | Error failure) {
            terminalFailure = failure;
            throw failure;
        } finally {
            telemetry.completed(terminalFailure);
        }
    }

    private String execute(Request request, BackendTelemetry telemetry) throws IOException {
        Objects.requireNonNull(request, "request");
        final long startedAt = telemetry.startedAt();
        final boolean retryAllowed = "GET".equals(request.method());
        IOException previousFailure = null;
        for (int attempt = 1; ; attempt++) {
            telemetry.phase("queue");
            long queueStartedAt = System.nanoTime();
            try {
                acquirePermit(startedAt, previousFailure, telemetry);
            } finally {
                telemetry.queued(System.nanoTime() - queueStartedAt);
            }
            Call call = null;
            IOException failure = null;
            String result = null;
            String retryAfter = null;
            int status = -1;
            boolean retryable = false;
            long attemptStartedAt = 0;
            long attemptBudget = 0;
            long attemptDuration = 0;
            Throwable attemptFailure = null;

            try {
                telemetry.phase("prepare");
                long remaining = remainingBudget(startedAt, previousFailure, telemetry);
                call = client.newCall(request);
                call.timeout().timeout(remaining, TimeUnit.NANOSECONDS);
                attemptBudget = remaining;
                attemptStartedAt = System.nanoTime();
                telemetry.attemptStarted();
                try (Response response = call.execute()) {
                    status = response.code();
                    telemetry.responseReceived(status);
                    if (response.isSuccessful()) {
                        telemetry.phase("body");
                        result = response.body().string();
                    } else {
                        failure = new IOException("Response code of DDB-API is " + status
                                + ". Request URL: " + response.request().url());
                        retryable = isTransientStatus(status);
                        retryAfter = response.header("Retry-After");
                    }
                    telemetry.phase("close");
                }
                if (failure != null) {
                    telemetry.phase("response");
                }
            } catch (IOException e) {
                failure = e;
                retryable = isTransientIoFailure(e);
            } catch (RuntimeException | Error e) {
                attemptFailure = e;
                throw e;
            } finally {
                if (attemptStartedAt != 0) {
                    attemptDuration = System.nanoTime() - attemptStartedAt;
                    telemetry.callCanceled(call.isCanceled());
                }
                // The response and its body are closed before admitting another call.
                permits.release();
                if (attemptFailure != null && attemptStartedAt != 0) {
                    telemetry.attemptCompleted(attemptDuration, attemptBudget, attemptFailure);
                }
            }

            try {
                if (failure == null) {
                    telemetry.phase("complete");
                    remainingBudget(startedAt, null, telemetry);
                }
            } catch (IOException e) {
                failure = e;
                throw e;
            } finally {
                if (attemptStartedAt != 0) {
                    telemetry.attemptCompleted(attemptDuration, attemptBudget, failure);
                }
            }
            if (failure == null) {
                return result;
            }
            if (Thread.currentThread().isInterrupted()) {
                throw interrupted("Backend request interrupted", failure);
            }
            if (!retryAllowed || !retryable || attempt >= settings.maxAttempts()
                    || (call != null && call.isCanceled())) {
                throw failure;
            }

            long remaining = remainingBudget(startedAt, failure, telemetry);
            long delay = retryDelayNanos(attempt, retryAfter);
            if (delay >= remaining) {
                // Do not shorten a server's Retry-After to force in another attempt.
                throw failure;
            }
            telemetry.retryScheduled(delay, failure);
            telemetry.phase("backoff");
            long backoffStartedAt = System.nanoTime();
            try {
                TimeUnit.NANOSECONDS.sleep(delay);
            } catch (InterruptedException e) {
                throw interrupted("Backend retry interrupted", e);
            } finally {
                telemetry.backedOff(System.nanoTime() - backoffStartedAt);
            }
            previousFailure = failure;
        }
    }

    /**
     * Waits fairly using the remainder of the original monotonic budget. A queued
     * request never receives a fresh per-attempt timeout when it gains a permit.
     * The caller releases an acquired permit only after closing the response.
     */
    private void acquirePermit(long startedAt, IOException previousFailure, BackendTelemetry telemetry) throws IOException {
        long remaining = remainingBudget(startedAt, previousFailure, telemetry);
        try {
            if (!permits.tryAcquire(remaining, TimeUnit.NANOSECONDS)) {
                throw budgetExhausted(previousFailure, telemetry);
            }
        } catch (InterruptedException e) {
            throw interrupted("Interrupted while waiting for a backend concurrency slot", e);
        }
    }

    /** Uses elapsed nanoTime rather than wall-clock time; retries never reset it. */
    private long remainingBudget(long startedAt, IOException previousFailure, BackendTelemetry telemetry) throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw interrupted("Backend request interrupted", previousFailure);
        }
        long remaining = requestBudgetNanos - (System.nanoTime() - startedAt);
        if (remaining <= 0) {
            throw budgetExhausted(previousFailure, telemetry);
        }
        return remaining;
    }

    private InterruptedIOException budgetExhausted(IOException previousFailure, BackendTelemetry telemetry) {
        InterruptedIOException failure = new InterruptedIOException("Backend request time budget of "
                + settings.requestTimeout().toMillis() + " ms exhausted");
        if (previousFailure != null) {
            failure.initCause(previousFailure);
        }
        telemetry.budgetExpired(failure);
        return failure;
    }

    private static InterruptedIOException interrupted(String message, Throwable cause) {
        Thread.currentThread().interrupt();
        InterruptedIOException failure = new InterruptedIOException(message);
        if (cause != null) {
            failure.initCause(cause);
        }
        return failure;
    }

    /** Retry only explicit overload, timeout and temporary backend failure statuses. */
    private static boolean isTransientStatus(int status) {
        return switch (status) {
            case 408, 429, 500, 502, 503, 504 -> true;
            default -> false;
        };
    }

    /**
     * Early connection failures may recover, but a timeout gives no assurance
     * that the backend stopped its search. Retrying an unanswered request could
     * duplicate expensive work, so all InterruptedIOException cases (including
     * socket timeouts), trust and protocol failures are excluded, also when wrapped.
     */
    private static boolean isTransientIoFailure(IOException failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (!visited.add(cause)) {
                // A malformed cause graph must not trap the request in an unbounded scan.
                return false;
            }
            if (cause instanceof InterruptedIOException || cause instanceof ProtocolException
                    || cause instanceof CertificateException
                    || cause instanceof SSLHandshakeException || cause instanceof SSLPeerUnverifiedException
                    || cause instanceof SSLProtocolException) {
                return false;
            }
        }
        return true;
    }

    /**
     * Applies equal jitter at 50-100% of a capped exponential backoff (attempts
     * are one-based). Retry-After is a separate lower bound and may exceed the
     * client cap; execute then stops if that wait cannot fit the remaining budget.
     */
    long retryDelayNanos(int failedAttempt, String retryAfter) {
        long base = settings.retryDelay().toNanos();
        long maximum = settings.maxRetryDelay().toNanos();
        int exponent = Math.min(Math.max(failedAttempt - 1, 0), 62);
        long multiplier = 1L << exponent;
        long capped = base > maximum / multiplier ? maximum : base * multiplier;
        long jittered = capped <= 1 ? capped : ThreadLocalRandom.current().nextLong(capped / 2, capped);
        return Math.max(jittered, retryAfterNanos(retryAfter));
    }

    /**
     * Accepts delta-seconds and RFC 1123 dates. Overflow and unrecognized nonblank
     * values fail closed: Long.MAX_VALUE prevents an accidentally early retry.
     * A missing or blank header imposes no additional minimum delay.
     */
    private static long retryAfterNanos(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        String trimmed = value.trim();
        if (trimmed.chars().allMatch(c -> c >= '0' && c <= '9')) {
            try {
                return Duration.ofSeconds(Long.parseLong(trimmed)).toNanos();
            } catch (NumberFormatException | ArithmeticException e) {
                return Long.MAX_VALUE;
            }
        }
        try {
            Instant retryAt = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            Duration delay = Duration.between(Instant.now(), retryAt);
            if (delay.isNegative()) {
                return 0;
            }
            try {
                return delay.toNanos();
            } catch (ArithmeticException e) {
                return Long.MAX_VALUE;
            }
        } catch (DateTimeParseException e) {
            // Unknown or obsolete HTTP-date formats must not trigger an early retry.
            return Long.MAX_VALUE;
        }
    }
}
