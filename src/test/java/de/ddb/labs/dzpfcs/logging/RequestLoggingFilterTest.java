package de.ddb.labs.dzpfcs.logging;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.CloseableThreadContext;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.message.ObjectMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests the servlet boundary with in-memory request/response proxies. The chain
 * controls parameter decoding and writes directly to a visible output stream;
 * no container, network, buffering layer or global Log4j reconfiguration is used.
 */
@Isolated("Temporarily captures the RequestLoggingFilter logger")
class RequestLoggingFilterTest {

    private final RequestLoggingFilter filter = new RequestLoggingFilter(null);

    @ParameterizedTest
    @ValueSource(strings = {"kube-probe/1.32", "kube-probe/1.33", "kube-probe/2.0", "kube-probe/"})
    void excludesProbeVersionsWhilePreservingTheChainRequestIdAndScopeCleanup(String userAgent) throws Exception {
        RequestDouble probe = new RequestDouble();
        probe.headers.put("User-Agent", userAgent);
        ResponseDouble response = new ResponseDouble();
        byte[] body = "<healthy/>".getBytes(StandardCharsets.UTF_8);

        try (CloseableThreadContext.Instance ignored = CloseableThreadContext.put("requestId", "outer");
                CaptureAppender logs = new CaptureAppender()) {
            filter.doFilter(probe.proxy, response.proxy, (input, output) -> {
                probe.enterChain();
                assertNotEquals("outer", ThreadContext.get("requestId"));
                RequestTelemetry.searchReturned(99, 1);
                output.getOutputStream().write(body);
            });

            assertTrue(logs.events.isEmpty(), "Routine probe completions must be excluded");
            assertArrayEquals(body, response.output.bytes.toByteArray());
            assertEquals("outer", ThreadContext.get("requestId"));
            assertEquals(response.headers.get("X-Request-ID"),
                    UUID.fromString(response.headers.get("X-Request-ID")).toString());

            RequestDouble next = new RequestDouble();
            ResponseDouble nextResponse = new ResponseDouble();
            filter.doFilter(next.proxy, nextResponse.proxy, (input, output) -> next.enterChain());
            LogEvent completion = logs.onlyCompletion();
            assertEquals(0, payload(completion).get("backendCalls"));
            assertEquals("not_invoked", payload(completion).get("engineOutcome"));
            assertNull(payload(completion).get("numFound"));
            assertNotEquals(response.headers.get("X-Request-ID"), nextResponse.headers.get("X-Request-ID"));
            assertEquals("outer", ThreadContext.get("requestId"));
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"Browser/1.0", "prefix-kube-probe/1.32", "KUBE-PROBE/1.32", "kube-probe"})
    void logsMissingOrNonmatchingUserAgents(String userAgent) throws Exception {
        RequestDouble request = new RequestDouble();
        request.headers.put("User-Agent", userAgent);
        try (CaptureAppender logs = new CaptureAppender()) {
            filter.doFilter(request.proxy, new ResponseDouble().proxy, (input, output) -> request.enterChain());
            assertEquals(Level.INFO, logs.onlyCompletion().getLevel());
        }
    }

    @ParameterizedTest
    @CsvSource({
        "my-monitor/1.0,false", "my-monitor/2.0,false", "internal.health/7,false",
        "kube-probe/1.32,true", "internalXhealth/7,true", "prefix-my-monitor/1.0,true"
    })
    void usesConfiguredTrimmedLiteralPrefixesInsteadOfTheDefaults(String userAgent, boolean logged) throws Exception {
        RequestLoggingFilter configured = new RequestLoggingFilter(" my-monitor/ , internal.health/ , , my-monitor/ ");
        RequestDouble request = new RequestDouble();
        request.headers.put("User-Agent", userAgent);
        try (CaptureAppender logs = new CaptureAppender()) {
            configured.doFilter(request.proxy, new ResponseDouble().proxy, (input, output) -> request.enterChain());
            if (logged) {
                assertEquals(Level.INFO, logs.onlyCompletion().getLevel());
            } else {
                assertTrue(logs.events.isEmpty());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", " , , "})
    void emptyConfigurationLogsProbeCompletionsAgain(String configuration) throws Exception {
        RequestDouble request = new RequestDouble();
        request.headers.put("User-Agent", "kube-probe/1.32");
        try (CaptureAppender logs = new CaptureAppender()) {
            new RequestLoggingFilter(configuration).doFilter(request.proxy, new ResponseDouble().proxy,
                    (input, output) -> request.enterChain());
            assertEquals(Level.INFO, logs.onlyCompletion().getLevel());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 500, 503})
    void keepsProbeCompletionsWithHttpErrors(int status) throws Exception {
        RequestDouble request = new RequestDouble();
        request.headers.put("User-Agent", "kube-probe/1.32");
        ResponseDouble response = new ResponseDouble();
        response.status = status;
        try (CaptureAppender logs = new CaptureAppender()) {
            filter.doFilter(request.proxy, response.proxy, (input, output) -> request.enterChain());
            assertEquals(status >= 500 ? Level.ERROR : Level.WARN, logs.onlyCompletion().getLevel());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"diagnostic", "exception", "timeout", "backendFailure", "partial"})
    void keepsProbeCompletionsWithApplicationWarningsEvenWhenHttpStatusIs200(String problem) throws Exception {
        RequestDouble request = new RequestDouble();
        request.headers.put("User-Agent", "kube-probe/1.32");
        try (CaptureAppender logs = new CaptureAppender()) {
            filter.doFilter(request.proxy, new ResponseDouble().proxy, (input, output) -> {
                request.enterChain();
                switch (problem) {
                    case "diagnostic", "exception" -> RequestTelemetry.searchFailed(problem, null);
                    case "timeout" -> RequestTelemetry.recordBackend(100, 50, 25, 25,
                            2, 1, 1, 200, "success", null);
                    case "backendFailure" -> RequestTelemetry.recordBackend(100, 50, 25, 25,
                            1, 0, 0, 503, "error", null);
                    case "partial" -> RequestTelemetry.recordSolrTiming(12L, true);
                    default -> throw new AssertionError(problem);
                }
            });
            assertEquals(Level.WARN, logs.onlyCompletion().getLevel());
        }
    }

    @Test
    void recordsOneCompletionWithOriginalMetadataTypedTimingsAndUnbufferedBytes() throws Exception {
        RequestDouble request = new RequestDouble();
        request.headers.put("User-Agent", "Browser/1.0 \"quoted\"\nnext line");
        request.headers.put("X-Forwarded-For", "203.0.113.7, 192.0.2.5\nnot trusted");
        request.headers.put("X-Request-ID", "client-controlled-id");
        request.parameters.put("query", "title=\"München\"\nOR \"Berlin\"");
        ResponseDouble response = new ResponseDouble();
        byte[] xml = "<record>Grüße</record>".getBytes(StandardCharsets.UTF_8);

        try (CloseableThreadContext.Instance ignored = CloseableThreadContext
                .put("requestId", "outer-request").put("retainedContext", "keep");
                CaptureAppender logs = new CaptureAppender()) {
            filter.doFilter(request.proxy, response.proxy, (input, output) -> {
                request.enterChain();
                input.setCharacterEncoding("UTF-8");
                assertEquals(request.parameters.get("query"), input.getParameter("query"));
                assertNotEquals("outer-request", ThreadContext.get("requestId"));
                RequestTelemetry.recordBackend(2_000_000L, 1_000_000L, 250_000L, 750_000L,
                        2, 1, 0, 200, "success", null);
                RequestTelemetry.recordBackend(3_000_000L, 2_000_000L, 500_000L, 500_000L,
                        1, 0, 0, 200, "success", null);
                RequestTelemetry.searchReturned(42, 1);
                RequestTelemetry.searchFinished(6_000_000L);
                RequestTelemetry.recordSolrTiming(3L, false);
                output.getOutputStream().write(xml);
                assertArrayEquals(xml, response.output.bytes.toByteArray(),
                        "Writes must reach the container stream before the chain returns");
            });

            LogEvent completion = logs.onlyCompletion();
            Map<?, ?> fields = payload(completion);
            String generatedId = response.headers.get("X-Request-ID");
            assertNotNull(generatedId);
            assertEquals(generatedId, UUID.fromString(generatedId).toString());
            assertNotEquals(request.headers.get("X-Request-ID"), generatedId);
            assertEquals(generatedId, completion.getContextData().getValue("requestId"));
            assertEquals("outer-request", ThreadContext.get("requestId"));
            assertEquals("keep", ThreadContext.get("retainedContext"));
            assertEquals("UTF-8", request.characterEncoding);
            assertTrue(request.parameterReads > 0);
            assertEquals(request.parameters.get("query"), fields.get("query"));
            assertEquals(request.headers.get("User-Agent"), fields.get("clientUserAgent"));
            assertEquals(request.headers.get("X-Request-ID"), fields.get("upstreamRequestId"));
            assertEquals("198.51.100.10", fields.get("clientAddress"));
            assertEquals(request.headers.get("X-Forwarded-For"), fields.get("forwardedFor"));
            assertEquals("POST", fields.get("httpMethod"));
            assertEquals("/dzp-fcs/sru", fields.get("path"));
            assertEquals("returned", fields.get("chainOutcome"));
            assertEquals("returned", fields.get("engineOutcome"));
            assertEquals(42L, fields.get("numFound"));
            assertEquals(1, fields.get("returnedRecords"));
            assertEquals(xml.length, number(fields, "responseBytes").longValue());
            assertEquals(2, number(fields, "backendCalls").intValue());
            assertEquals(3, number(fields, "backendAttempts").intValue());
            assertEquals(1, number(fields, "backendRetries").intValue());
            assertEquals(5.0, number(fields, "backendDurationMs").doubleValue());
            assertEquals(3.0, number(fields, "backendActiveMs").doubleValue());
            assertEquals(0.75, number(fields, "backendQueueMs").doubleValue());
            assertEquals(1.25, number(fields, "backendBackoffMs").doubleValue());
            assertEquals(6.0, number(fields, "searchDurationMs").doubleValue());
            assertEquals(3L, number(fields, "solrQTimeMs").longValue());
            assertEquals(false, fields.get("solrPartialResults"));
            assertTrue(number(fields, "requestDurationMs").doubleValue() >= 0);
            assertEquals(Level.INFO, completion.getLevel());
        }
    }

    @Test
    void preservesTheOriginalExceptionAndRestoresContextBeforeTheThreadIsReused() throws Exception {
        RequestDouble firstRequest = new RequestDouble();
        firstRequest.headers.put("User-Agent", "kube-probe/1.32");
        ResponseDouble firstResponse = new ResponseDouble();
        ServletException original = new ServletException("original servlet failure");

        try (CloseableThreadContext.Instance ignored = CloseableThreadContext.put("requestId", "outer");
                CaptureAppender logs = new CaptureAppender()) {
            ServletException thrown = assertThrows(ServletException.class,
                    () -> filter.doFilter(firstRequest.proxy, firstResponse.proxy, (input, output) -> {
                        firstRequest.enterChain();
                        RequestTelemetry.recordBackend(1_000_000L, 1_000_000L, 0, 0,
                                1, 0, 0, 500, "failure", null);
                        throw original;
                    }));
            assertSame(original, thrown);
            assertEquals("outer", ThreadContext.get("requestId"));
            LogEvent failure = logs.onlyCompletion();
            assertEquals("threw", payload(failure).get("chainOutcome"));
            assertEquals(ServletException.class.getName(), payload(failure).get("errorClass"));
            assertSame(original, failure.getThrown());
            assertEquals(Level.ERROR, failure.getLevel());

            RequestDouble secondRequest = new RequestDouble();
            ResponseDouble secondResponse = new ResponseDouble();
            filter.doFilter(secondRequest.proxy, secondResponse.proxy,
                    (input, output) -> secondRequest.enterChain());

            assertEquals(2, logs.events.size(), "Each request must emit exactly one completion");
            LogEvent next = logs.events.get(1);
            assertEquals("request.completed", payload(next).get("event"));
            assertEquals(0, number(payload(next), "backendCalls").intValue());
            assertEquals("not_invoked", payload(next).get("engineOutcome"));
            assertNull(next.getThrown());
            assertNotEquals(firstResponse.headers.get("X-Request-ID"),
                    secondResponse.headers.get("X-Request-ID"));
            assertEquals("outer", ThreadContext.get("requestId"));
        }
    }

    @Test
    void recordsAnOutputFailureEvenWhenTheFrameworkSwallowsIt() throws Exception {
        RequestDouble request = new RequestDouble();
        request.headers.put("User-Agent", "kube-probe/1.32");
        ResponseDouble response = new ResponseDouble();
        response.output.failure = new IOException("client disconnected");

        try (CaptureAppender logs = new CaptureAppender()) {
            filter.doFilter(request.proxy, response.proxy, (input, output) -> {
                request.enterChain();
                assertThrows(IOException.class, () -> output.getOutputStream().write(1));
            });

            LogEvent completion = logs.onlyCompletion();
            Map<?, ?> fields = payload(completion);
            assertEquals("returned", fields.get("chainOutcome"));
            assertEquals(IOException.class.getName(), fields.get("responseWriteErrorClass"));
            assertEquals(0L, number(fields, "responseBytes").longValue());
            assertNull(fields.get("errorClass"));
            assertNull(completion.getThrown());
            assertEquals(Level.WARN, completion.getLevel());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"streamAcquisition", "responseFlush", "streamFlush", "streamClose"})
    void recordsContainerOutputFailuresBeyondWriteCalls(String operation) throws Exception {
        RequestDouble request = new RequestDouble();
        request.headers.put("User-Agent", "kube-probe/1.32");
        ResponseDouble response = new ResponseDouble();
        IOException original = new IOException("container output failed");
        if ("streamAcquisition".equals(operation)) {
            response.streamAcquisitionFailure = original;
        } else {
            response.output.failure = original;
        }

        try (CaptureAppender logs = new CaptureAppender()) {
            filter.doFilter(request.proxy, response.proxy, (input, output) -> {
                request.enterChain();
                IOException thrown = assertThrows(IOException.class, () -> {
                    switch (operation) {
                        case "streamAcquisition" -> output.getOutputStream();
                        case "responseFlush" -> output.flushBuffer();
                        case "streamFlush" -> output.getOutputStream().flush();
                        case "streamClose" -> output.getOutputStream().close();
                        default -> throw new AssertionError(operation);
                    }
                });
                assertSame(original, thrown, "Observation must preserve the container's exception");
            });

            LogEvent completion = logs.onlyCompletion();
            assertEquals("returned", payload(completion).get("chainOutcome"));
            assertEquals(IOException.class.getName(), payload(completion).get("responseWriteErrorClass"));
            assertEquals(0L, number(payload(completion), "responseBytes").longValue());
            assertNull(completion.getThrown());
            assertEquals(Level.WARN, completion.getLevel());
        }
    }

    @Test
    void doesNotMaskARealServletExceptionAfterAnOutputFailure() {
        RequestDouble request = new RequestDouble();
        ResponseDouble response = new ResponseDouble();
        response.output.failure = new IOException("write failed first");
        ServletException original = new ServletException("actual chain failure");

        try (CaptureAppender logs = new CaptureAppender()) {
            ServletException thrown = assertThrows(ServletException.class,
                    () -> filter.doFilter(request.proxy, response.proxy, (input, output) -> {
                        request.enterChain();
                        assertThrows(IOException.class,
                                () -> output.getOutputStream().write(new byte[]{1, 2, 3}));
                        throw original;
                    }));

            assertSame(original, thrown);
            LogEvent completion = logs.onlyCompletion();
            assertEquals(IOException.class.getName(), payload(completion).get("responseWriteErrorClass"));
            assertEquals(ServletException.class.getName(), payload(completion).get("errorClass"));
            assertSame(original, completion.getThrown());
            assertEquals(Level.ERROR, completion.getLevel());
        }
    }

    @Test
    void keepsTheGeneratedRequestIdAcrossResetAndCountsOnlyRemainingOutput() throws Exception {
        RequestDouble request = new RequestDouble();
        ResponseDouble response = new ResponseDouble();
        byte[] retained = "<response>ä</response>".getBytes(StandardCharsets.UTF_8);

        try (CaptureAppender logs = new CaptureAppender()) {
            filter.doFilter(request.proxy, response.proxy, (input, output) -> {
                request.enterChain();
                HttpServletResponse httpOutput = (HttpServletResponse) output;
                String requestId = httpOutput.getHeader("X-Request-ID");
                output.getOutputStream().write("first discarded bytes".getBytes(StandardCharsets.UTF_8));
                output.resetBuffer();
                assertEquals(0, response.output.bytes.size());
                assertEquals(requestId, httpOutput.getHeader("X-Request-ID"));
                output.getOutputStream().write("second discarded bytes".getBytes(StandardCharsets.UTF_8));
                output.reset();
                assertEquals(0, response.output.bytes.size());
                assertEquals(requestId, httpOutput.getHeader("X-Request-ID"));
                output.getOutputStream().write(retained);
                assertArrayEquals(retained, response.output.bytes.toByteArray());
                assertEquals(2, response.streamAcquisitions,
                        "Only reset(), unlike resetBuffer(), clears the servlet's stream selection");
            });

            LogEvent completion = logs.onlyCompletion();
            assertEquals(retained.length, number(payload(completion), "responseBytes").longValue());
            assertEquals(response.headers.get("X-Request-ID"),
                    completion.getContextData().getValue("requestId"));
        }
    }

    @Test
    void logsParameterReadFailuresWithoutTurningACompletedRequestIntoAnException() throws Exception {
        RequestDouble request = new RequestDouble();
        request.headers.put("User-Agent", "kube-probe/1.32");
        request.parameterFailure = new IllegalStateException("container parameter parsing failed");
        ResponseDouble response = new ResponseDouble();

        try (CaptureAppender logs = new CaptureAppender()) {
            filter.doFilter(request.proxy, response.proxy, (input, output) -> request.enterChain());

            LogEvent completion = logs.onlyCompletion();
            assertEquals(IllegalStateException.class.getName(),
                    payload(completion).get("parameterLogErrorClass"));
            assertEquals(Level.WARN, completion.getLevel());
            assertEquals("returned", payload(completion).get("chainOutcome"));
            assertNull(completion.getThrown());
        }
    }

    private static Number number(Map<?, ?> fields, String key) {
        return assertInstanceOf(Number.class, fields.get(key), key + " must be numeric");
    }

    private static Map<?, ?> payload(LogEvent event) {
        ObjectMessage message = assertInstanceOf(ObjectMessage.class, event.getMessage());
        return assertInstanceOf(Map.class, message.getParameter());
    }

    private static <T> T servletProxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static final class RequestDouble {
        private final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        private final Map<String, String> parameters = new LinkedHashMap<>();
        private final HttpServletRequest proxy;
        private boolean chainEntered;
        private int parameterReads;
        private String characterEncoding = "ISO-8859-1";
        private RuntimeException parameterFailure;

        private RequestDouble() {
            parameters.put("operation", "searchRetrieve");
            parameters.put("query", "Berlin");
            parameters.put("queryType", "cql");
            parameters.put("startRecord", "1");
            parameters.put("maximumRecords", "10");
            proxy = servletProxy(HttpServletRequest.class, (object, method, arguments) -> switch (method.getName()) {
                case "getMethod" -> "POST";
                case "getRequestURI" -> "/dzp-fcs/sru";
                case "getRemoteAddr" -> "198.51.100.10";
                case "getHeader" -> headers.get((String) arguments[0]);
                case "getCharacterEncoding" -> characterEncoding;
                case "setCharacterEncoding" -> {
                    characterEncoding = (String) arguments[0];
                    yield null;
                }
                case "getParameter" -> {
                    assertTrue(chainEntered, "Logging must not decode POST parameters before the servlet chain");
                    parameterReads++;
                    if (parameterFailure != null) {
                        throw parameterFailure;
                    }
                    yield parameters.get((String) arguments[0]);
                }
                case "toString" -> "RequestDouble";
                default -> throw new UnsupportedOperationException(method.getName());
            });
        }

        private void enterChain() {
            chainEntered = true;
        }
    }

    private static final class ResponseDouble {
        private final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        private final VisibleOutputStream output = new VisibleOutputStream();
        private final HttpServletResponse proxy;
        private int status = 200;
        private IOException streamAcquisitionFailure;
        private int streamAcquisitions;

        private ResponseDouble() {
            proxy = servletProxy(HttpServletResponse.class, (object, method, arguments) -> switch (method.getName()) {
                case "setHeader", "addHeader" -> {
                    headers.put((String) arguments[0], (String) arguments[1]);
                    yield null;
                }
                case "getHeader" -> headers.get((String) arguments[0]);
                case "containsHeader" -> headers.containsKey((String) arguments[0]);
                case "getStatus" -> status;
                case "setStatus" -> {
                    status = (Integer) arguments[0];
                    yield null;
                }
                case "getOutputStream" -> {
                    streamAcquisitions++;
                    if (streamAcquisitionFailure != null) {
                        throw streamAcquisitionFailure;
                    }
                    yield output;
                }
                case "flushBuffer" -> {
                    output.flush();
                    yield null;
                }
                case "isCommitted" -> false;
                case "reset" -> {
                    headers.clear();
                    status = 200;
                    output.bytes.reset();
                    yield null;
                }
                case "resetBuffer" -> {
                    output.bytes.reset();
                    yield null;
                }
                case "toString" -> "ResponseDouble";
                default -> throw new UnsupportedOperationException(method.getName());
            });
        }
    }

    /** Makes every accepted byte observable while permitting deterministic write failures. */
    private static final class VisibleOutputStream extends ServletOutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private IOException failure;

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setWriteListener(WriteListener listener) {
            throw new UnsupportedOperationException("Only synchronous writes are used");
        }

        @Override
        public void write(int value) throws IOException {
            if (failure != null) {
                throw failure;
            }
            bytes.write(value);
        }

        @Override
        public void write(byte[] source, int offset, int length) throws IOException {
            if (failure != null) {
                throw failure;
            }
            bytes.write(source, offset, length);
        }

        @Override
        public void flush() throws IOException {
            if (failure != null) {
                throw failure;
            }
        }

        @Override
        public void close() throws IOException {
            if (failure != null) {
                throw failure;
            }
        }
    }

    private static final class CaptureAppender extends AbstractAppender implements AutoCloseable {
        private final org.apache.logging.log4j.core.Logger logger;
        private final List<LogEvent> events = new ArrayList<>();

        private CaptureAppender() {
            super("RequestLoggingFilterTestCapture", null, null, true, Property.EMPTY_ARRAY);
            logger = (org.apache.logging.log4j.core.Logger) LogManager.getLogger(RequestLoggingFilter.class);
            start();
            logger.addAppender(this);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }

        private LogEvent onlyCompletion() {
            assertEquals(1, events.size(), "Exactly one completion event must be logged");
            LogEvent event = events.get(0);
            assertEquals("request.completed", payload(event).get("event"));
            return event;
        }

        @Override
        public void close() {
            logger.removeAppender(this);
            stop();
        }
    }
}
