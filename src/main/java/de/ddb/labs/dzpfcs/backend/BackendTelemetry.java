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

import de.ddb.labs.dzpfcs.logging.RequestTelemetry;
import de.ddb.labs.dzpfcs.logging.StructuredLog;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;

/**
 * Per-invocation measurements for logical backend calls, not OkHttp's internal
 * exchanges. This mutable object is confined to the executing request thread;
 * the executor itself remains safe to share. Timings use the same monotonic clock
 * as its deadline and contain no request URL, query, or exception message.
 */
final class BackendTelemetry {

    private final Logger logger;
    private final LongSupplier nanoTime;
    private final long startedAt;
    private final long requestBudgetNanos;
    private final int maxAttempts;
    private final String backendCallId;
    // Successful calls need none of these identity collections. Allocate only
    // when classifying a failure or recording an executor-owned deadline.
    private Map<Throwable, String> explicitTimeouts;
    private Map<Throwable, String> observedTimeouts;
    private Set<Throwable> countedTimeouts;
    private long activeNanos;
    private long queueNanos;
    private long backoffNanos;
    private int attempts;
    private int timeouts;
    private Integer status;
    private String phase = "queue";
    private boolean canceled;

    BackendTelemetry(Logger logger, long requestBudgetNanos, int maxAttempts) {
        this(logger, requestBudgetNanos, maxAttempts, System::nanoTime);
    }

    /** A monotonic clock seam keeps deadline-boundary classification tests deterministic. */
    BackendTelemetry(Logger logger, long requestBudgetNanos, int maxAttempts, LongSupplier nanoTime) {
        this.logger = logger;
        this.requestBudgetNanos = requestBudgetNanos;
        this.maxAttempts = maxAttempts;
        this.nanoTime = nanoTime;
        this.startedAt = nanoTime.getAsLong();
        this.backendCallId = UUID.randomUUID().toString();
    }

    long startedAt() {
        return startedAt;
    }

    void phase(String phase) {
        this.phase = phase;
    }

    void queued(long durationNanos) {
        queueNanos += durationNanos;
    }

    void backedOff(long durationNanos) {
        backoffNanos += durationNanos;
    }

    /** Marks executor-owned deadline failures without parsing exception text. */
    void budgetExpired(Throwable failure) {
        if (explicitTimeouts == null) {
            explicitTimeouts = new IdentityHashMap<>();
        }
        explicitTimeouts.put(failure, "queue".equals(phase) ? "queue" : "budget");
    }

    void attemptStarted() {
        attempts++;
        status = null;
        phase = "call";
        canceled = false;
    }

    void responseReceived(int status) {
        this.status = status;
        phase = "response";
    }

    void callCanceled(boolean canceled) {
        this.canceled = canceled;
    }

    /** Called after response close, including errors raised by the final budget check. */
    void attemptCompleted(long durationNanos, long attemptBudgetNanos, Throwable failure) {
        activeNanos += durationNanos;
        String timeoutType = timeoutType(failure);
        countTimeout(failure, timeoutType);
        Map<String, Object> fields = fields();
        fields.put("attempt", attempts);
        fields.put("maxAttempts", maxAttempts);
        fields.put("requestBudgetMs", millis(requestBudgetNanos));
        fields.put("attemptBudgetMs", millis(attemptBudgetNanos));
        fields.put("durationMs", millis(durationNanos));
        fields.put("status", status);
        fields.put("errorClass", errorClass(failure));
        fields.put("timeoutType", timeoutType);
        fields.put("phase", phase);
        StructuredLog.event(logger, failure == null ? Level.INFO : Level.WARN,
                "backend.attempt.completed", fields);
    }

    void retryScheduled(long delayNanos, Throwable failure) {
        Map<String, Object> fields = fields();
        fields.put("attempt", attempts);
        fields.put("maxAttempts", maxAttempts);
        fields.put("delayMs", millis(delayNanos));
        fields.put("status", status);
        fields.put("reason", status == null ? errorClass(failure) : "http");
        StructuredLog.event(logger, Level.WARN, "backend.retry.scheduled", fields);
    }

    /**
     * Emits one summary per executor invocation, even without an acquired permit.
     * Retry counts include started attempts after the first, rather than merely
     * scheduled retries whose backoff may have been interrupted.
     */
    void completed(Throwable failure) {
        long durationNanos = nanoTime.getAsLong() - startedAt;
        String timeoutType = timeoutType(failure);
        countTimeout(failure, timeoutType);
        String outcome = failure == null ? "success"
                : Thread.currentThread().isInterrupted() ? "interrupted"
                : timeoutType != null ? "timeout" : canceled ? "canceled" : "error";
        int retries = Math.max(0, attempts - 1);
        Map<String, Object> fields = fields();
        fields.put("backendDurationMs", millis(durationNanos));
        fields.put("backendActiveMs", millis(activeNanos));
        fields.put("backendQueueMs", millis(queueNanos));
        fields.put("backendBackoffMs", millis(backoffNanos));
        fields.put("backendAttempts", attempts);
        fields.put("backendRetries", retries);
        fields.put("backendTimeouts", timeouts);
        fields.put("backendStatus", status);
        fields.put("outcome", outcome);
        fields.put("errorClass", errorClass(failure));
        fields.put("timeoutType", timeoutType);
        fields.put("phase", failure == null ? "complete" : phase);
        RequestTelemetry.recordBackend(durationNanos, activeNanos, queueNanos, backoffNanos,
                attempts, retries, timeouts, status, outcome, timeoutType);
        StructuredLog.event(logger, failure == null ? Level.INFO : Level.WARN, "backend.completed", fields);
    }

    private Map<String, Object> fields() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("backendCallId", backendCallId);
        return fields;
    }

    private String timeoutType(Throwable failure) {
        if (failure == null || Thread.currentThread().isInterrupted()) {
            return null;
        }
        String explicit = explicitTimeouts == null ? null : explicitTimeouts.get(failure);
        if (explicit != null) {
            return explicit;
        }
        // Logging can block past the deadline. Preserve the first observation,
        // including a non-timeout, instead of reclassifying the same error later.
        if (observedTimeouts != null && observedTimeouts.containsKey(failure)) {
            return observedTimeouts.get(failure);
        }
        String observed = classifyTimeout(failure);
        if (observedTimeouts == null) {
            observedTimeouts = new IdentityHashMap<>();
        }
        observedTimeouts.put(failure, observed);
        return observed;
    }

    private String classifyTimeout(Throwable failure) {
        boolean socketTimeout = false;
        boolean interruptedIo = false;
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause()) {
            socketTimeout |= cause instanceof SocketTimeoutException;
            interruptedIo |= cause instanceof InterruptedIOException;
        }
        // A native call deadline may wrap a socket error. The elapsed shared
        // deadline takes precedence, without guessing DNS/connect/read phases.
        if (interruptedIo && nanoTime.getAsLong() - startedAt >= requestBudgetNanos) {
            return "budget";
        }
        if (socketTimeout) {
            return "socket";
        }
        return status != null && (status == 408 || status == 504) ? "upstream" : null;
    }

    private void countTimeout(Throwable failure, String timeoutType) {
        if (timeoutType == null) {
            return;
        }
        if (countedTimeouts == null) {
            countedTimeouts = Collections.newSetFromMap(new IdentityHashMap<>());
        }
        if (countedTimeouts.add(failure)) {
            timeouts++;
        }
    }

    private static String errorClass(Throwable failure) {
        return failure == null ? null : failure.getClass().getSimpleName();
    }

    /** Milliseconds remain JSON numbers, with microsecond precision. */
    private static double millis(long nanos) {
        return Math.round(nanos / 1_000.0) / 1_000.0;
    }
}
