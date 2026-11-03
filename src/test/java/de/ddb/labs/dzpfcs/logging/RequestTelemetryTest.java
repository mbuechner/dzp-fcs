package de.ddb.labs.dzpfcs.logging;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.ThreadContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Protects request isolation and MDC cleanup on reused servlet threads. */
class RequestTelemetryTest {

    @Test
    void nestedScopesRestoreTheirOwnCountersAndTheExistingLoggingContext() {
        String priorId = ThreadContext.get("requestId");
        ThreadContext.put("requestId", "pre-existing");
        try {
            try (RequestTelemetry.Scope outer = RequestTelemetry.open("outer")) {
                RequestTelemetry.searchReturned(53, 25);
                RequestTelemetry.recordSolrTiming(17L, false);
                RequestTelemetry.searchFinished(5_000_000);
                RequestTelemetry.recordBackend(4_000_000, 3_000_000, 500_000, 400_000,
                        2, 1, 1, 200, "success", null);

                try (RequestTelemetry.Scope inner = RequestTelemetry.open("inner")) {
                    assertEquals("inner", ThreadContext.get("requestId"));
                    assertEquals(0, inner.fields().get("backendCalls"));
                    RequestTelemetry.searchFailed("diagnostic", "info:srw/diagnostic/1/61");
                    assertEquals("diagnostic", inner.fields().get("engineOutcome"));
                }

                Map<String, Object> fields = outer.fields();
                assertEquals("outer", ThreadContext.get("requestId"));
                assertEquals("returned", fields.get("engineOutcome"));
                assertEquals(53L, fields.get("numFound"));
                assertEquals(25, fields.get("returnedRecords"));
                assertEquals(17L, fields.get("solrQTimeMs"));
                assertEquals(5.0, fields.get("searchDurationMs"));
                assertEquals(4.0, fields.get("backendDurationMs"));
                assertEquals(3.0, fields.get("backendActiveMs"));
                assertEquals(0.5, fields.get("backendQueueMs"));
                assertEquals(0.4, fields.get("backendBackoffMs"));
                assertEquals(2, fields.get("backendAttempts"));
                assertEquals(1, fields.get("backendRetries"));
                assertEquals(1, fields.get("backendTimeouts"));
                assertNull(fields.get("sruDiagnostic"));
            }
            assertEquals("pre-existing", ThreadContext.get("requestId"));
        } finally {
            if (priorId == null) {
                ThreadContext.remove("requestId");
            } else {
                ThreadContext.put("requestId", priorId);
            }
        }
    }

    @Test
    void exceptionCleanupPreventsMetricsFromLeakingToTheNextRequest() {
        assertThrows(IllegalStateException.class, () -> {
            try (RequestTelemetry.Scope ignored = RequestTelemetry.open("failed-request")) {
                RequestTelemetry.recordBackend(10, 5, 2, 1, 3, 2, 1, 504, "timeout", "upstream");
                throw new IllegalStateException("simulated servlet error");
            }
        });

        // Measurements outside a filter scope must neither fail nor carry over.
        RequestTelemetry.recordBackend(10, 5, 2, 1, 1, 0, 0, 200, "success", null);
        try (RequestTelemetry.Scope next = RequestTelemetry.open("next-request")) {
            assertEquals(0, next.fields().get("backendCalls"));
            assertEquals(0, next.fields().get("backendAttempts"));
            assertEquals("not_invoked", next.fields().get("engineOutcome"));
        }
    }

    @Test
    void concurrentRequestsCannotReadEachOthersCountersOrIds() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        try {
            Future<Map<String, Object>> first = workers.submit(() -> isolatedSnapshot("first", 2, ready));
            Future<Map<String, Object>> second = workers.submit(() -> isolatedSnapshot("second", 7, ready));

            assertEquals(2, first.get(5, TimeUnit.SECONDS).get("backendAttempts"));
            assertEquals(7, second.get(5, TimeUnit.SECONDS).get("backendAttempts"));
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void appendsScalarSnapshotsWithoutChangingExistingRequestMetadata() {
        try (RequestTelemetry.Scope scope = RequestTelemetry.open("snapshot-request")) {
            Map<String, Object> completion = new LinkedHashMap<>();
            completion.put("query", "Berlin");
            RequestTelemetry.recordBackend(4_000_000, 3_000_000, 500_000, 500_000,
                    2, 1, 1, 200, "success", null);

            scope.appendFieldsTo(completion);
            RequestTelemetry.recordBackend(8_000_000, 6_000_000, 1_000_000, 1_000_000,
                    1, 0, 0, 503, "error", null);

            assertEquals("Berlin", completion.get("query"));
            assertEquals(1, completion.get("backendCalls"));
            assertEquals(4.0, completion.get("backendDurationMs"));
            assertEquals(200, completion.get("backendStatus"));
            Map<String, Object> current = scope.fields();
            assertEquals(2, current.get("backendCalls"));
            assertEquals(12.0, current.get("backendDurationMs"));
            assertEquals(503, current.get("backendStatus"));
            assertEquals("error", current.get("backendOutcome"));
        }
    }

    private static Map<String, Object> isolatedSnapshot(String id, int attempts, CountDownLatch ready) throws Exception {
        try (RequestTelemetry.Scope scope = RequestTelemetry.open(id)) {
            RequestTelemetry.recordBackend(10, 5, 2, 1, attempts, attempts - 1, 0, 200, "success", null);
            ready.countDown();
            assertTrue(ready.await(3, TimeUnit.SECONDS));
            assertEquals(id, ThreadContext.get("requestId"));
            return scope.fields();
        }
    }
}
