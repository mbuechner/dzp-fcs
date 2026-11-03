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
package de.ddb.labs.dzpfcs.logging;

import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.logging.log4j.CloseableThreadContext;

/**
 * Request-scoped measurements for the synchronous SRU servlet pipeline.
 *
 * <p>A filter installs one instance per request. Backend calls and the search
 * engine contribute timings without depending on servlet APIs. There is no
 * shared mutable counter across requests: the thread-local scope and Log4j MDC
 * are restored together, including when a container thread is reused after an
 * exception. Calls made outside an HTTP request remain fully functional.</p>
 *
 * <p>All contributions and scope operations must run on the owning servlet
 * thread. A scope is closed in reverse opening order and is not propagated to
 * executor tasks. This thread confinement is why the counters need no locks.</p>
 *
 * <p>Engine return is deliberately different from final SRU success: the
 * framework can generate diagnostics after the search engine has returned.</p>
 */
public final class RequestTelemetry {

    private static final ThreadLocal<RequestTelemetry> CURRENT = new ThreadLocal<>();

    private String engineOutcome = "not_invoked";
    private Long searchDurationNanos;
    private Long solrQTimeMs;
    private Boolean solrPartialResults;
    private Long numFound;
    private Integer returnedRecords;
    private String sruDiagnostic;
    private int backendCalls;
    private long backendDurationNanos;
    private long backendActiveNanos;
    private long backendQueueNanos;
    private long backendBackoffNanos;
    private int backendAttempts;
    private int backendRetries;
    private int backendTimeouts;
    private Integer backendStatus;
    private String backendOutcome;
    private String backendTimeoutType;

    private RequestTelemetry() {
    }

    /**
     * Install a fresh scope while preserving the enclosing telemetry and MDC.
     * Only the {@code requestId} MDC key is changed; unrelated context survives.
     */
    public static Scope open(String requestId) {
        RequestTelemetry previous = CURRENT.get();
        RequestTelemetry current = new RequestTelemetry();
        CloseableThreadContext.Instance logging = CloseableThreadContext.put("requestId", requestId);
        CURRENT.set(current);
        return new Scope(current, previous, logging);
    }

    public static void searchReturned(long total, int records) {
        RequestTelemetry current = CURRENT.get();
        if (current != null) {
            current.engineOutcome = "returned";
            current.numFound = total;
            current.returnedRecords = records;
        }
    }

    public static void searchFailed(String outcome, String diagnostic) {
        RequestTelemetry current = CURRENT.get();
        if (current != null) {
            current.engineOutcome = outcome;
            current.sruDiagnostic = diagnostic;
        }
    }

    public static void searchFinished(long durationNanos) {
        RequestTelemetry current = CURRENT.get();
        if (current != null) {
            current.searchDurationNanos = durationNanos;
        }
    }

    /** QTime belongs to the final successful Solr response, not to the whole retry sequence. */
    public static void recordSolrTiming(Long qTimeMs, Boolean partialResults) {
        RequestTelemetry current = CURRENT.get();
        if (current != null) {
            current.solrQTimeMs = qTimeMs;
            current.solrPartialResults = partialResults;
        }
    }

    /**
     * Accumulate a completed backend operation, including failed queue acquisition.
     * Durations are monotonic nanoseconds internally and emitted as numeric
     * milliseconds. Attempts/retries count application calls, not OkHttp's internal
     * recovery exchanges. A recovered timeout is still included in the counter.
     */
    public static void recordBackend(long durationNanos, long activeNanos, long queueNanos, long backoffNanos,
            int attempts, int retries, int timeouts, Integer status, String outcome, String timeoutType) {
        RequestTelemetry current = CURRENT.get();
        if (current != null) {
            current.backendCalls++;
            current.backendDurationNanos += durationNanos;
            current.backendActiveNanos += activeNanos;
            current.backendQueueNanos += queueNanos;
            current.backendBackoffNanos += backoffNanos;
            current.backendAttempts += attempts;
            current.backendRetries += retries;
            current.backendTimeouts += timeouts;
            current.backendStatus = status;
            current.backendOutcome = outcome;
            current.backendTimeoutType = timeoutType;
        }
    }

    /** Snapshot the measurements independently of later scope contributions. */
    private Map<String, Object> fields() {
        Map<String, Object> fields = new LinkedHashMap<>();
        appendFieldsTo(fields);
        return fields;
    }

    /**
     * Copy measurements into the completion payload without allocating an
     * intermediate map. Values are immutable scalars, so later contributions do
     * not change the copied measurements. Durations and counters accumulate over
     * logical backend calls; status, outcome and timeout type describe the last
     * completed call. Optional measurements remain null until observed.
     *
     * @param fields caller-owned payload to receive the telemetry keys
     */
    private void appendFieldsTo(Map<String, Object> fields) {
        fields.put("engineOutcome", engineOutcome);
        fields.put("searchDurationMs", searchDurationNanos == null ? null : millis(searchDurationNanos));
        fields.put("solrQTimeMs", solrQTimeMs);
        fields.put("solrPartialResults", solrPartialResults);
        fields.put("numFound", numFound);
        fields.put("returnedRecords", returnedRecords);
        fields.put("sruDiagnostic", sruDiagnostic);
        fields.put("backendCalls", backendCalls);
        fields.put("backendDurationMs", millis(backendDurationNanos));
        fields.put("backendActiveMs", millis(backendActiveNanos));
        fields.put("backendQueueMs", millis(backendQueueNanos));
        fields.put("backendBackoffMs", millis(backendBackoffNanos));
        fields.put("backendAttempts", backendAttempts);
        fields.put("backendRetries", backendRetries);
        fields.put("backendTimeouts", backendTimeouts);
        fields.put("backendStatus", backendStatus);
        fields.put("backendOutcome", backendOutcome);
        fields.put("backendTimeoutType", backendTimeoutType);
    }

    /** Milliseconds with microsecond precision; JSON values remain numbers. */
    public static double millis(long nanos) {
        return Math.round(nanos / 1000.0) / 1000.0;
    }

    /** Thread-confined scope, closed once in reverse opening order. */
    public static final class Scope implements AutoCloseable {
        private final RequestTelemetry telemetry;
        private final RequestTelemetry previous;
        private final CloseableThreadContext.Instance logging;

        private Scope(RequestTelemetry telemetry, RequestTelemetry previous, CloseableThreadContext.Instance logging) {
            this.telemetry = telemetry;
            this.previous = previous;
            this.logging = logging;
        }

        /** Return an independent snapshot without exposing the mutable request state. */
        public Map<String, Object> fields() {
            return telemetry.fields();
        }

        /** Append directly to the filter's payload without an intermediate snapshot. */
        void appendFieldsTo(Map<String, Object> fields) {
            telemetry.appendFieldsTo(fields);
        }

        @Override
        public void close() {
            try {
                logging.close();
            } finally {
                if (previous == null) {
                    CURRENT.remove();
                } else {
                    CURRENT.set(previous);
                }
            }
        }
    }
}
