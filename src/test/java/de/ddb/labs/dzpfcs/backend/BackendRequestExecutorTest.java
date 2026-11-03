package de.ddb.labs.dzpfcs.backend;

import de.ddb.labs.dzpfcs.logging.RequestTelemetry;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.ProtocolException;
import java.net.SocketTimeoutException;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLProtocolException;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;
import org.apache.logging.log4j.CloseableThreadContext;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.message.ObjectMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises retry policy with real OkHttp calls terminated by application
 * interceptors. These doubles do not model socket stalls or watchdog cancellation;
 * those require the separate local-network runtime tests.
 */
class BackendRequestExecutorTest {

    private static final Request GET = new Request.Builder()
            .url("https://example.invalid/search?q=private-search-text")
            .build();
    private static final String JSON = "{\"response\":{\"numFound\":0,\"docs\":[]}}";

    @ParameterizedTest
    @ValueSource(ints = {408, 429, 500, 502, 503, 504})
    void retriesTransientStatusesAndClosesEachResponseBeforeRetry(int status) throws Exception {
        ScriptedBackend backend = new ScriptedBackend(response(status, "error", "0"), response(200, JSON, null));
        BackendRequestExecutor executor = executor(backend, settings(3));

        assertEquals(JSON, executor.execute(GET));

        assertEquals(2, backend.requests.size());
        assertEquals(GET.url(), backend.requests.get(1).url());
        assertTrue(backend.bodies.stream().allMatch(body -> body.closed));
        assertTrue(backend.timeouts.get(0) <= Duration.ofSeconds(2).toNanos());
        assertTrue(backend.timeouts.get(1) > 0);
        assertTrue(backend.timeouts.get(1) < backend.timeouts.get(0));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404})
    void doesNotRetryPermanentHttpErrors(int status) {
        ScriptedBackend backend = new ScriptedBackend(response(status, "error", null));

        IOException failure = assertThrows(IOException.class, () -> executor(backend, settings(3)).execute(GET));

        assertEquals("Response code of DDB-API is " + status + ". Request URL: " + GET.url(), failure.getMessage());
        assertEquals(1, backend.requests.size());
        assertTrue(backend.bodies.get(0).closed);
    }

    @Test
    void stopsAtTheConfiguredLogicalAttemptLimit() {
        ScriptedBackend backend = new ScriptedBackend(response(503, "first", null),
                response(503, "second", null), response(503, "third", null));

        IOException failure = assertThrows(IOException.class, () -> executor(backend, settings(3)).execute(GET));

        assertTrue(failure.getMessage().startsWith("Response code of DDB-API is 503."));
        assertEquals(3, backend.requests.size());
        assertTrue(backend.bodies.stream().allMatch(body -> body.closed));
    }

    @Test
    void doesNotRetryPostRequests() {
        Request post = GET.newBuilder()
                .post(RequestBody.create("input", MediaType.get("text/plain")))
                .build();
        ScriptedBackend backend = new ScriptedBackend(response(503, "error", null));

        assertThrows(IOException.class, () -> executor(backend, settings(3)).execute(post));

        assertEquals(1, backend.requests.size());
        assertTrue(backend.bodies.get(0).closed);
    }

    @ParameterizedTest
    @MethodSource("transientIoFailures")
    void retriesSuitableTransportFailures(IOException transportFailure) throws Exception {
        ScriptedBackend backend = new ScriptedBackend(failure(transportFailure), response(200, JSON, null));

        assertEquals(JSON, executor(backend, settings(3)).execute(GET));

        assertEquals(2, backend.requests.size());
        assertTrue(backend.bodies.get(0).closed);
    }

    static Stream<IOException> transientIoFailures() {
        return Stream.of(new IOException("connection reset"), new ConnectException("connection refused"));
    }

    @Test
    void retriesBodyReadFailuresAfterClosingTheFailedResponse() throws Exception {
        ScriptedBackend backend = new ScriptedBackend((chain, script) -> {
            TrackedBody body = new TrackedBody(new IOException("body read failed"));
            script.bodies.add(body);
            return responseBuilder(chain, 200, body).build();
        }, response(200, JSON, null));

        assertEquals(JSON, executor(backend, settings(3)).execute(GET));

        assertEquals(2, backend.requests.size());
        assertTrue(backend.bodies.stream().allMatch(body -> body.closed));
    }

    @Test
    void socketReadTimeoutDoesNotDuplicateTheBackendSearchAndClosesTheResponse() {
        SocketTimeoutException timeout = new SocketTimeoutException("read timed out");
        ScriptedBackend backend = new ScriptedBackend((chain, script) -> {
            TrackedBody body = new TrackedBody(timeout);
            script.bodies.add(body);
            return responseBuilder(chain, 200, body).build();
        });

        IOException failure = assertThrows(IOException.class, () -> executor(backend, settings(3)).execute(GET));

        assertSame(timeout, failure);
        assertEquals(1, backend.requests.size());
        assertTrue(backend.bodies.get(0).closed);
    }

    @ParameterizedTest
    @MethodSource("permanentIoFailures")
    void doesNotRetryProtocolCertificateOrInterruptionFailures(IOException original) {
        ScriptedBackend backend = new ScriptedBackend(failure(original));

        IOException failure = assertThrows(IOException.class, () -> executor(backend, settings(3)).execute(GET));

        assertSame(original, failure);
        assertEquals(1, backend.requests.size());
    }

    static Stream<IOException> permanentIoFailures() {
        SSLHandshakeException certificateFailure = new SSLHandshakeException("untrusted certificate");
        certificateFailure.initCause(new CertificateException("untrusted issuer"));
        return Stream.of(new ProtocolException("invalid response"), certificateFailure,
                new SSLPeerUnverifiedException("hostname mismatch"), new SSLProtocolException("invalid TLS"),
                new InterruptedIOException("interrupted read"), new SocketTimeoutException("unanswered request"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void boundsCyclicCauseGraphsWithoutRetryingOrLosingTimeoutClassification(boolean socketCause) {
        CyclicCauseIOException original = new CyclicCauseIOException();
        IOException linked = socketCause ? new SocketTimeoutException("private-search-text")
                : new IOException("private-search-text");
        original.initCause(linked);
        linked.initCause(original);
        ScriptedBackend backend = new ScriptedBackend(failure(original), response(200, JSON, null));
        try (TelemetryCapture telemetry = new TelemetryCapture()) {
            IOException failure = assertThrows(IOException.class,
                    () -> executor(backend, settings(3)).execute(GET));

            assertSame(original, failure);
            assertEquals(1, backend.requests.size(), "Cyclic failure graphs must fail closed");
            String expectedType = socketCause ? "socket" : null;
            Map<String, Object> attempt = telemetry.single("backend.attempt.completed");
            Map<String, Object> summary = telemetry.single("backend.completed");
            assertEquals(expectedType, attempt.get("timeoutType"));
            assertEquals(expectedType, summary.get("timeoutType"));
            assertEquals(socketCause ? "timeout" : "error", summary.get("outcome"));
            assertEquals(socketCause ? 1 : 0, summary.get("backendTimeouts"));
            assertEquals(1, summary.get("backendAttempts"));
            assertTrue(telemetry.events("backend.retry.scheduled").isEmpty());
            telemetry.assertPrivateAndCorrelated(null);
        }
    }

    @Test
    void doesNotRetryCanceledCalls() {
        ScriptedBackend backend = new ScriptedBackend((chain, script) -> {
            chain.call().cancel();
            throw new IOException("Canceled");
        });

        assertThrows(IOException.class, () -> executor(backend, settings(3)).execute(GET));

        assertEquals(1, backend.requests.size());
    }

    @Test
    void preservesAnExistingThreadInterruptWithoutStartingACall() {
        ScriptedBackend backend = new ScriptedBackend();
        try {
            Thread.currentThread().interrupt();

            assertThrows(InterruptedIOException.class, () -> executor(backend, settings(3)).execute(GET));

            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(backend.requests.isEmpty());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void returnsSuccessfulBodiesWithoutParsingOrRetryingInvalidJson() throws Exception {
        ScriptedBackend backend = new ScriptedBackend(response(200, "invalid JSON", null));

        assertEquals("invalid JSON", executor(backend, settings(3)).execute(GET));

        assertEquals(1, backend.requests.size());
        assertTrue(backend.bodies.get(0).closed);
    }

    @Test
    void doesNotShortenRetryAfterToFitTheRemainingBudget() {
        ScriptedBackend backend = new ScriptedBackend(response(429, "busy", "60"));

        IOException failure = assertThrows(IOException.class, () -> executor(backend, settings(3)).execute(GET));

        assertTrue(failure.getMessage().startsWith("Response code of DDB-API is 429."));
        assertEquals(1, backend.requests.size());
        assertTrue(backend.bodies.get(0).closed);
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown-format", "Sunday, 06-Nov-94 08:49:37 GMT", "Sun Nov  6 08:49:37 1994"})
    void conservativelyStopsWhenRetryAfterCannotBeInterpreted(String retryAfter) {
        ScriptedBackend backend = new ScriptedBackend(response(429, "busy", retryAfter));

        IOException failure = assertThrows(IOException.class, () -> executor(backend, settings(3)).execute(GET));

        assertTrue(failure.getMessage().startsWith("Response code of DDB-API is 429."));
        assertEquals(1, backend.requests.size());
        assertTrue(backend.bodies.get(0).closed);
    }

    @Test
    void boundsExponentialJitterAndHonorsServerWaitsAboveTheClientCap() {
        BackendHttpSettings settings = new BackendHttpSettings(Duration.ofSeconds(1), Duration.ofSeconds(1),
                Duration.ofSeconds(2), 3, 1, Duration.ofMillis(100), Duration.ofMillis(400));
        BackendRequestExecutor executor = executor(new ScriptedBackend(), settings);

        for (int i = 0; i < 100; i++) {
            assertBetween(executor.retryDelayNanos(1, null), Duration.ofMillis(50), Duration.ofMillis(100));
            assertBetween(executor.retryDelayNanos(2, null), Duration.ofMillis(100), Duration.ofMillis(200));
            assertBetween(executor.retryDelayNanos(Integer.MAX_VALUE, null), Duration.ofMillis(200), Duration.ofMillis(400));
        }
        assertEquals(Duration.ofSeconds(5).toNanos(), executor.retryDelayNanos(1, "5"));
        assertEquals(Long.MAX_VALUE, executor.retryDelayNanos(1, "999999999999999999999999999999"));
        String future = ZonedDateTime.now(ZoneOffset.UTC).plusMinutes(10)
                .format(DateTimeFormatter.RFC_1123_DATE_TIME);
        assertBetween(executor.retryDelayNanos(1, future), Duration.ofSeconds(590), Duration.ofSeconds(601));
        assertEquals(Long.MAX_VALUE, executor.retryDelayNanos(1, "invalid"));
        assertBetween(executor.retryDelayNanos(1, "Wed, 01 Jan 2020 00:00:00 GMT"), Duration.ofMillis(50), Duration.ofMillis(100));
    }

    @Test
    void includesSemaphoreWaitingInTheBudgetAndReleasesThePermitAfterFailure() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<TrackedBody> firstBody = new AtomicReference<>();
        // The first interceptor deliberately ignores call-timeout cancellation.
        // Holding its permit isolates the second invocation's queue-wait budget.
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                try {
                    if (!release.await(2, TimeUnit.SECONDS)) {
                        throw new IOException("test gate timed out");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("test gate interrupted");
                }
            }
            TrackedBody body = new TrackedBody(JSON);
            firstBody.compareAndSet(null, body);
            return responseBuilder(chain, 200, body).build();
        }).build();
        BackendHttpSettings settings = new BackendHttpSettings(Duration.ofSeconds(1), Duration.ofSeconds(1),
                Duration.ofMillis(120), 1, 1, Duration.ZERO, Duration.ZERO);
        BackendRequestExecutor executor = new BackendRequestExecutor(client, settings);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (TelemetryCapture telemetry = new TelemetryCapture()) {
            Future<String> first = worker.submit(() -> executor.execute(GET));
            assertTrue(entered.await(2, TimeUnit.SECONDS));

            InterruptedIOException failure = assertThrows(InterruptedIOException.class, () -> executor.execute(GET));

            assertTrue(failure.getMessage().contains("time budget"));
            assertEquals(1, calls.get());
            // The first call has also spent its budget and must fail after release.
            release.countDown();
            ExecutionException firstFailure = assertThrows(ExecutionException.class, () -> first.get(2, TimeUnit.SECONDS));
            assertInstanceOf(IOException.class, firstFailure.getCause());
            assertTrue(firstBody.get().closed);
            assertEquals(JSON, executor.execute(GET));
            assertEquals(2, calls.get());
            List<Map<String, Object>> summaries = telemetry.events("backend.completed");
            assertEquals(3, summaries.size());
            Map<String, Object> queued = summaries.stream()
                    .filter(event -> Integer.valueOf(0).equals(event.get("backendAttempts")))
                    .findFirst().orElseThrow();
            assertEquals("timeout", queued.get("outcome"));
            assertEquals("queue", queued.get("timeoutType"));
            assertEquals("queue", queued.get("phase"));
            assertEquals(1, queued.get("backendTimeouts"));
            assertEquals(0.0, queued.get("backendActiveMs"));
            assertTrue(number(queued, "backendQueueMs") >= 50);
            assertEquals(2, telemetry.events("backend.attempt.completed").size());
            assertTrue(summaries.stream().anyMatch(event -> "budget".equals(event.get("timeoutType"))
                    && Integer.valueOf(1).equals(event.get("backendTimeouts"))
                    && Integer.valueOf(1).equals(event.get("backendAttempts"))));
        } finally {
            release.countDown();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void emitsOneTypedSummaryAndOneAttemptWithoutExposingSearchData() throws Exception {
        ScriptedBackend backend = new ScriptedBackend(response(200, JSON, null));
        try (TelemetryCapture telemetry = new TelemetryCapture();
                CloseableThreadContext.Instance context = CloseableThreadContext.put("requestId", "request-123")) {
            assertEquals(JSON, executor(backend, settings(3)).execute(GET));

            Map<String, Object> attempt = telemetry.single("backend.attempt.completed");
            Map<String, Object> summary = telemetry.single("backend.completed");
            assertEquals(1, attempt.get("attempt"));
            assertEquals(3, attempt.get("maxAttempts"));
            assertEquals(2000.0, attempt.get("requestBudgetMs"));
            assertTrue(number(attempt, "attemptBudgetMs") > 0);
            assertEquals(200, attempt.get("status"));
            assertNull(attempt.get("errorClass"));
            assertNull(attempt.get("timeoutType"));
            assertEquals("success", summary.get("outcome"));
            assertEquals(200, summary.get("backendStatus"));
            assertEquals(1, summary.get("backendAttempts"));
            assertEquals(0, summary.get("backendRetries"));
            assertEquals(0, summary.get("backendTimeouts"));
            assertEquals(attempt.get("backendCallId"), summary.get("backendCallId"));
            assertTrue(number(summary, "backendDurationMs") >= number(summary, "backendActiveMs"));
            assertEquals(0.0, summary.get("backendBackoffMs"));
            telemetry.assertPrivateAndCorrelated("request-123");
        }
    }

    @Test
    void recordsEachLogicalRetryAndAggregatesActualBackoff() throws Exception {
        ScriptedBackend backend = new ScriptedBackend(response(503, "private-upstream-body", null),
                response(200, JSON, null));
        BackendHttpSettings settings = new BackendHttpSettings(Duration.ofSeconds(1), Duration.ofSeconds(1),
                Duration.ofSeconds(2), 3, 1, Duration.ofMillis(20), Duration.ofMillis(20));
        try (TelemetryCapture telemetry = new TelemetryCapture()) {
            assertEquals(JSON, executor(backend, settings).execute(GET));

            List<Map<String, Object>> attempts = telemetry.events("backend.attempt.completed");
            Map<String, Object> retry = telemetry.single("backend.retry.scheduled");
            Map<String, Object> summary = telemetry.single("backend.completed");
            assertEquals(2, attempts.size());
            assertEquals(503, attempts.get(0).get("status"));
            assertEquals("response", attempts.get(0).get("phase"));
            assertEquals("IOException", attempts.get(0).get("errorClass"));
            assertEquals(200, attempts.get(1).get("status"));
            assertEquals(1, retry.get("attempt"));
            assertEquals("http", retry.get("reason"));
            assertTrue(number(retry, "delayMs") >= 10);
            assertTrue(number(summary, "backendBackoffMs") >= number(retry, "delayMs"));
            assertEquals(2, summary.get("backendAttempts"));
            assertEquals(1, summary.get("backendRetries"));
            assertEquals(0, summary.get("backendTimeouts"));
            assertEquals("success", summary.get("outcome"));
            assertNull(summary.get("errorClass"));
            assertNull(summary.get("timeoutType"));
            double activeSum = attempts.stream().mapToDouble(event -> number(event, "durationMs")).sum();
            assertTrue(Math.abs(activeSum - number(summary, "backendActiveMs")) <= 0.002);
            telemetry.assertPrivateAndCorrelated(null);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 504})
    void countsUpstreamTimeoutsEvenWhenTheNextAttemptSucceeds(int status) throws Exception {
        ScriptedBackend backend = new ScriptedBackend(response(status, "upstream error", null), response(200, JSON, null));
        try (TelemetryCapture telemetry = new TelemetryCapture()) {
            assertEquals(JSON, executor(backend, settings(3)).execute(GET));

            Map<String, Object> first = telemetry.events("backend.attempt.completed").get(0);
            Map<String, Object> summary = telemetry.single("backend.completed");
            assertEquals("upstream", first.get("timeoutType"));
            assertEquals(1, summary.get("backendTimeouts"));
            assertEquals("success", summary.get("outcome"));
            assertNull(summary.get("timeoutType"));
        }
    }

    @Test
    void recordsSocketTimeoutsWithoutGuessingConnectOrReadAndWithoutDoubleCounting() {
        ScriptedBackend backend = new ScriptedBackend((chain, script) -> {
            TrackedBody body = new TrackedBody(new SocketTimeoutException("private-search-text in an error"));
            script.bodies.add(body);
            return responseBuilder(chain, 200, body).build();
        });
        try (TelemetryCapture telemetry = new TelemetryCapture()) {
            assertThrows(SocketTimeoutException.class, () -> executor(backend, settings(3)).execute(GET));

            Map<String, Object> attempt = telemetry.single("backend.attempt.completed");
            Map<String, Object> summary = telemetry.single("backend.completed");
            assertEquals("socket", attempt.get("timeoutType"));
            assertEquals("body", attempt.get("phase"));
            assertEquals("timeout", summary.get("outcome"));
            assertEquals("socket", summary.get("timeoutType"));
            assertEquals(1, summary.get("backendTimeouts"));
            assertEquals(1, summary.get("backendAttempts"));
            assertEquals(200, summary.get("backendStatus"));
            assertTrue(backend.bodies.get(0).closed);
            assertTrue(telemetry.events("backend.retry.scheduled").isEmpty());
            telemetry.assertPrivateAndCorrelated(null);
        }
    }

    @Test
    void recordsAnInterruptedQueueWithoutStartingAnAttemptOrCountingATimeout() {
        try (TelemetryCapture telemetry = new TelemetryCapture()) {
            try {
                Thread.currentThread().interrupt();
                assertThrows(InterruptedIOException.class,
                        () -> executor(new ScriptedBackend(), settings(3)).execute(GET));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
            Map<String, Object> summary = telemetry.single("backend.completed");
            assertEquals("interrupted", summary.get("outcome"));
            assertEquals(0, summary.get("backendAttempts"));
            assertEquals(0, summary.get("backendRetries"));
            assertEquals(0, summary.get("backendTimeouts"));
            assertNull(summary.get("backendStatus"));
            assertNull(summary.get("timeoutType"));
            assertTrue(telemetry.events("backend.attempt.completed").isEmpty());
        }
    }

    @Test
    void recordsUnexpectedClientFailuresExactlyOnceAndPreservesTheirType() {
        IllegalStateException original = new IllegalStateException("private-search-text");
        ScriptedBackend backend = new ScriptedBackend((chain, script) -> {
            throw original;
        });
        try (TelemetryCapture telemetry = new TelemetryCapture()) {
            assertSame(original, assertThrows(IllegalStateException.class,
                    () -> executor(backend, settings(3)).execute(GET)));

            Map<String, Object> attempt = telemetry.single("backend.attempt.completed");
            Map<String, Object> summary = telemetry.single("backend.completed");
            assertEquals("IllegalStateException", attempt.get("errorClass"));
            assertEquals("error", summary.get("outcome"));
            assertEquals("IllegalStateException", summary.get("errorClass"));
            assertEquals(1, summary.get("backendAttempts"));
            telemetry.assertPrivateAndCorrelated(null);
        }
    }

    @Test
    void contributesActualCountsAndDurationsToOnlyTheCurrentRequestScope() throws Exception {
        ScriptedBackend backend = new ScriptedBackend(response(408, "upstream timeout", null),
                response(200, JSON, null), response(400, "bad request", null));
        BackendRequestExecutor executor = executor(backend, settings(3));
        RequestTelemetry.Scope requestScope;
        try (TelemetryCapture telemetry = new TelemetryCapture();
                RequestTelemetry.Scope request = RequestTelemetry.open("request-with-two-backend-calls")) {
            requestScope = request;
            assertEquals(JSON, executor.execute(GET));
            assertThrows(IOException.class, () -> executor.execute(GET));

            Map<String, Object> requestFields = requestScope.fields();
            List<Map<String, Object>> summaries = telemetry.events("backend.completed");
            assertEquals(2, summaries.size());
            assertEquals(2, requestFields.get("backendCalls"));
            assertEquals(3, requestFields.get("backendAttempts"));
            assertEquals(1, requestFields.get("backendRetries"));
            assertEquals(1, requestFields.get("backendTimeouts"));
            assertEquals(400, requestFields.get("backendStatus"));
            assertEquals("error", requestFields.get("backendOutcome"));
            assertNull(requestFields.get("backendTimeoutType"));
            double durationSum = summaries.stream().mapToDouble(event -> number(event, "backendDurationMs")).sum();
            assertTrue(Math.abs(durationSum - number(requestFields, "backendDurationMs")) <= 0.002);
            telemetry.assertPrivateAndCorrelated("request-with-two-backend-calls");
        }

        assertEquals(JSON, executor(new ScriptedBackend(response(200, JSON, null)), settings(1)).execute(GET));
        assertEquals(2, requestScope.fields().get("backendCalls"), "Closed scopes must not receive later calls");
    }

    @ParameterizedTest
    @MethodSource("initiallyObservedInterruptIoFailures")
    void slowLoggingCannotReclassifyAnAlreadyObservedFailureAfterTheBudgetExpires(InterruptedIOException failure) {
        AtomicLong nanoTime = new AtomicLong();
        BackendTelemetry observation = new BackendTelemetry(LogManager.getLogger(BackendRequestExecutor.class),
                1_000, 1, nanoTime::get);
        try (TelemetryCapture telemetry = new TelemetryCapture()) {
            observation.attemptStarted();
            observation.responseReceived(200);
            observation.phase("body");
            nanoTime.set(500);
            observation.attemptCompleted(500, 1_000, failure);

            // Model time spent in a slow synchronous logger without scheduler
            // races or sleeps. The error occurred before the shared deadline.
            nanoTime.set(2_000);
            observation.completed(failure);

            String expectedType = failure instanceof SocketTimeoutException ? "socket" : null;
            Map<String, Object> attempt = telemetry.single("backend.attempt.completed");
            Map<String, Object> summary = telemetry.single("backend.completed");
            assertEquals(expectedType, attempt.get("timeoutType"));
            assertEquals(expectedType, summary.get("timeoutType"));
            assertEquals(expectedType == null ? "error" : "timeout", summary.get("outcome"));
            assertEquals(expectedType == null ? 0 : 1, summary.get("backendTimeouts"));
        }
    }

    static Stream<InterruptedIOException> initiallyObservedInterruptIoFailures() {
        return Stream.of(new SocketTimeoutException("socket deadline"), new InterruptedIOException("interrupted I/O"));
    }

    @Test
    void explicitExecutorBudgetMarkingsTakePriorityOverAnEarlierNonTimeoutObservation() {
        AtomicLong nanoTime = new AtomicLong();
        BackendTelemetry observation = new BackendTelemetry(LogManager.getLogger(BackendRequestExecutor.class),
                1_000, 1, nanoTime::get);
        InterruptedIOException failure = new InterruptedIOException("deadline");
        try (TelemetryCapture telemetry = new TelemetryCapture()) {
            observation.attemptStarted();
            observation.phase("call");
            nanoTime.set(500);
            observation.attemptCompleted(500, 1_000, failure);
            assertNull(telemetry.single("backend.attempt.completed").get("timeoutType"));

            nanoTime.set(2_000);
            observation.budgetExpired(failure);
            observation.completed(failure);

            Map<String, Object> summary = telemetry.single("backend.completed");
            assertEquals("budget", summary.get("timeoutType"));
            assertEquals("timeout", summary.get("outcome"));
            assertEquals(1, summary.get("backendTimeouts"));
        }
    }

    private static double number(Map<String, Object> event, String key) {
        return assertInstanceOf(Number.class, event.get(key)).doubleValue();
    }

    /** Captures the typed message payload before the production JSON layout runs. */
    private static final class TelemetryCapture extends AbstractAppender implements AutoCloseable {
        private final List<LogEvent> captured = new CopyOnWriteArrayList<>();
        private final org.apache.logging.log4j.core.Logger logger =
                (org.apache.logging.log4j.core.Logger) LogManager.getLogger(BackendRequestExecutor.class);
        private final Level previousLevel = logger.getLevel();

        TelemetryCapture() {
            super("backend-telemetry-test-" + System.nanoTime(), null, null, true, Property.EMPTY_ARRAY);
            start();
            logger.addAppender(this);
            logger.setLevel(Level.INFO);
        }

        @Override
        public void append(LogEvent event) {
            captured.add(event.toImmutable());
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events(String name) {
            return captured.stream().map(event -> event.getMessage())
                    .filter(ObjectMessage.class::isInstance).map(ObjectMessage.class::cast)
                    .map(ObjectMessage::getParameter).filter(Map.class::isInstance)
                    .map(value -> (Map<String, Object>) value)
                    .filter(value -> name.equals(value.get("event"))).toList();
        }

        Map<String, Object> single(String name) {
            List<Map<String, Object>> events = events(name);
            assertEquals(1, events.size(), name);
            return events.get(0);
        }

        void assertPrivateAndCorrelated(String requestId) {
            assertTrue(!captured.isEmpty());
            for (LogEvent event : captured) {
                assertNull(event.getThrown());
                assertTrue(!event.getMessage().getFormattedMessage().contains("private-search-text"));
                assertTrue(!event.getMessage().getFormattedMessage().contains("example.invalid"));
                assertTrue(!event.getMessage().getFormattedMessage().contains("private-upstream-body"));
                if (requestId != null) {
                    assertEquals(requestId, event.getContextData().getValue("requestId"));
                }
            }
        }

        @Override
        public void close() {
            logger.removeAppender(this);
            logger.setLevel(previousLevel);
            stop();
        }
    }

    private static BackendHttpSettings settings(int attempts) {
        return new BackendHttpSettings(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(2),
                attempts, 2, Duration.ZERO, Duration.ZERO);
    }

    private static BackendRequestExecutor executor(ScriptedBackend backend, BackendHttpSettings settings) {
        return new BackendRequestExecutor(new OkHttpClient.Builder().addInterceptor(backend).build(), settings);
    }

    private static void assertBetween(long actual, Duration minimum, Duration maximum) {
        assertTrue(actual >= minimum.toNanos() && actual <= maximum.toNanos(),
                () -> "Delay " + actual + " is outside " + minimum + ".." + maximum);
    }

    private static Step failure(IOException failure) {
        return (chain, script) -> {
            throw failure;
        };
    }

    private static Step response(int status, String content, String retryAfter) {
        return (chain, script) -> {
            TrackedBody body = new TrackedBody(content);
            script.bodies.add(body);
            Response.Builder response = responseBuilder(chain, status, body);
            if (retryAfter != null) {
                response.header("Retry-After", retryAfter);
            }
            return response.build();
        };
    }

    private static Response.Builder responseBuilder(Interceptor.Chain chain, int status, ResponseBody body) {
        return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(status).message("in-memory backend").body(body);
    }

    @FunctionalInterface
    private interface Step {
        Response respond(Interceptor.Chain chain, ScriptedBackend script) throws IOException;
    }

    /** Fail a regressed unbounded traversal promptly rather than hanging the test JVM. */
    private static final class CyclicCauseIOException extends IOException {
        private static final long serialVersionUID = 1L;
        private int causeReads;

        CyclicCauseIOException() {
            super("private-search-text");
        }

        @Override
        public synchronized Throwable getCause() {
            if (++causeReads > 16) {
                throw new AssertionError("Exception cause graph was traversed without a cycle bound");
            }
            return super.getCause();
        }
    }

    /** Scripts logical calls; native socket-level recovery is outside this double. */
    private static final class ScriptedBackend implements Interceptor {
        private final List<Step> steps;
        private final List<Request> requests = new ArrayList<>();
        private final List<Long> timeouts = new ArrayList<>();
        private final List<TrackedBody> bodies = new ArrayList<>();

        ScriptedBackend(Step... steps) {
            this.steps = List.of(steps);
        }

        @Override
        public Response intercept(Chain chain) throws IOException {
            if (!bodies.isEmpty()) {
                assertTrue(bodies.get(bodies.size() - 1).closed, "Previous response must be closed before retry");
            }
            int index = requests.size();
            requests.add(chain.request());
            timeouts.add(chain.call().timeout().timeoutNanos());
            assertTrue(index < steps.size(), "Unexpected additional backend call");
            return steps.get(index).respond(chain, this);
        }
    }

    private static final class TrackedBody extends ResponseBody {
        private final BufferedSource source;
        private boolean closed;

        TrackedBody(String content) {
            this.source = new Buffer().writeUtf8(content);
        }

        TrackedBody(IOException failure) {
            this.source = Okio.buffer(new ForwardingSource(new Buffer()) {
                @Override
                public long read(Buffer sink, long byteCount) throws IOException {
                    throw failure;
                }
            });
        }

        @Override
        public MediaType contentType() {
            return MediaType.get("application/json; charset=utf-8");
        }

        @Override
        public long contentLength() {
            return -1;
        }

        @Override
        public BufferedSource source() {
            return source;
        }

        @Override
        public void close() {
            // string() closes the source too; this flag specifically proves that
            // the surrounding response was closed, rather than merely consumed.
            closed = true;
            super.close();
        }
    }
}
