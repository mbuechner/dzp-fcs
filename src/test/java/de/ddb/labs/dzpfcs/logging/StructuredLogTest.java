package de.ddb.labs.dzpfcs.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.ConfigurationSource;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.config.xml.XmlConfiguration;
import org.apache.logging.log4j.message.ObjectMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exercises the production logging configuration and JSON template together.
 * Assertions concern parseable single-line events, typed metrics and escaped
 * request data rather than a particular ordering of serialized JSON fields.
 */
class StructuredLogTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private LoggerContext context;
    private Logger logger;
    private CaptureAppender capture;
    private Map<String, String> previousThreadContext;

    @BeforeEach
    void loadProductionConfiguration() throws Exception {
        previousThreadContext = ThreadContext.getImmutableContext();
        ThreadContext.clearMap();
        URL resource = StructuredLogTest.class.getResource("/log4j2.xml");
        assertNotNull(resource);
        context = new LoggerContext("structured-log-test-" + UUID.randomUUID());
        try (InputStream input = resource.openStream()) {
            context.start(new XmlConfiguration(context, new ConfigurationSource(input, resource)));
        }
        Configuration configuration = context.getConfiguration();
        Layout<?> productionLayout = configuration.getAppender("Console").getLayout();
        capture = new CaptureAppender(productionLayout);
        capture.start();
        configuration.getRootLogger().removeAppender("Console");
        configuration.getRootLogger().addAppender(capture, null, null);
        context.updateLoggers();
        logger = context.getLogger("structured-log-contract");
    }

    @AfterEach
    void restoreLoggingContext() {
        if (context != null) {
            context.stop();
        }
        ThreadContext.clearMap();
        if (previousThreadContext != null) {
            ThreadContext.putAll(previousThreadContext);
        }
    }

    @Test
    void preservesTypedMetricsAndEscapesRequestDataIntoOneJsonLine() {
        String query = "title=\"Berlin\"\nOR forged=true\r\n\\path";
        String userAgent = "Client/1.0 (\"quoted\")\nsecond line";
        String requestId = "request-\"one\"\n";
        ThreadContext.put("requestId", requestId);

        StructuredLog.event(logger, Level.INFO, "search.completed", Map.of(
                "query", query,
                "userAgent", userAgent,
                "elapsedMillis", 1234567890123L,
                "backendMillis", 12.5,
                "timedOut", false,
                "details", Map.of("attempts", 2, "statuses", List.of(502, 200))));

        JsonNode event = onlyJsonEvent();
        JsonNode message = event.path("message");
        assertEquals("search.completed", message.path("event").asString());
        assertEquals(query, message.path("query").asString());
        assertEquals(userAgent, message.path("userAgent").asString());
        assertTrue(message.path("elapsedMillis").isIntegralNumber());
        assertEquals(1234567890123L, message.path("elapsedMillis").asLong());
        assertTrue(message.path("backendMillis").isNumber());
        assertEquals(12.5, message.path("backendMillis").asDouble());
        assertTrue(message.path("timedOut").isBoolean());
        assertFalse(message.path("timedOut").asBoolean());
        assertEquals(2, message.path("details").path("attempts").asInt());
        assertEquals(502, message.path("details").path("statuses").get(0).asInt());
        assertEquals(requestId, event.path("requestId").asString());
        assertEquals("INFO", event.path("level").asString());
        assertEquals(logger.getName(), event.path("logger").asString());
        assertEquals(Thread.currentThread().getName(), event.path("thread").asString());
        Instant.parse(event.path("timestamp").asString());
    }

    @Test
    void retainsSearchTextBeyondTheLayoutsDefaultStringLimit() {
        String query = "Berlin ".repeat(3000) + "END-OF-QUERY";

        StructuredLog.event(logger, Level.INFO, "request.completed", Map.of("query", query));

        assertEquals(query, onlyJsonEvent().path("message").path("query").asString());
    }

    @Test
    void preservesExceptionalLongQueriesInBoundedUnicodeSafeChunks() {
        String query = "x".repeat(32767) + "\uD83D\uDE80" + "\\\"\nBerlin".repeat(6000) + "END-OF-QUERY";

        StructuredLog.event(logger, Level.INFO, "request.completed", Map.of("query", query));

        JsonNode message = onlyJsonEvent().path("message");
        assertTrue(message.path("queryChunked").asBoolean());
        assertEquals(query.length(), message.path("queryLength").asInt());
        StringBuilder reconstructed = new StringBuilder();
        for (JsonNode chunk : message.path("queryChunks")) {
            String text = chunk.asString();
            assertTrue(text.length() <= 32768);
            assertFalse(Character.isHighSurrogate(text.charAt(text.length() - 1)));
            reconstructed.append(text);
        }
        assertEquals(query, reconstructed.toString());
    }

    @Test
    void serializesFrameworkMessagesAsEscapedJsonStrings() {
        String frameworkMessage = "Framework \"message\"\nwith another line";
        logger.info(frameworkMessage);

        JsonNode event = onlyJsonEvent();
        assertTrue(event.path("message").isString());
        assertEquals(frameworkMessage, event.path("message").asString());
        assertFalse(event.has("requestId"));
    }

    @Test
    void rendersExceptionsSeparatelyWithAnEscapedStackTrace() {
        IllegalStateException failure = new IllegalStateException("upstream \"failed\"\nnext line",
                new IllegalArgumentException("nested failure"));

        StructuredLog.event(logger, Level.ERROR, "search.failed", Map.of("attempts", 3), failure);

        JsonNode event = onlyJsonEvent();
        assertEquals("search.failed", event.path("message").path("event").asString());
        JsonNode exception = event.path("exception");
        assertEquals(IllegalStateException.class.getName(), exception.path("type").asString());
        assertEquals(failure.getMessage(), exception.path("message").asString());
        String stackTrace = exception.path("stackTrace").asString();
        assertTrue(stackTrace.contains("nested failure"));
        assertTrue(stackTrace.contains("StructuredLogTest"));
        assertTrue(stackTrace.contains("\n"));
    }

    @Test
    void snapshotsFieldsOmitsOptionalNullsAndProtectsTheEventName() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("elapsedMillis", 7L);
        fields.put("optional", null);
        fields.put(null, "ignored");
        fields.put("event", "caller override");

        StructuredLog.event(logger, Level.INFO, "search.completed", fields);
        fields.put("elapsedMillis", 999L);

        ObjectMessage message = (ObjectMessage) capture.events.get(0).getMessage();
        Map<?, ?> payload = (Map<?, ?>) message.getParameter();
        assertEquals(7L, payload.get("elapsedMillis"));
        assertEquals("search.completed", payload.get("event"));
        assertFalse(payload.containsKey("optional"));
        assertFalse(payload.containsKey(null));
        assertThrows(UnsupportedOperationException.class, payload::clear);
        assertFalse(onlyJsonEvent().path("message").has("optional"));
    }

    @Test
    void respectsDisabledLogLevels() {
        Map<String, Object> expensiveFields = new AbstractMap<>() {
            @Override
            public Set<Map.Entry<String, Object>> entrySet() {
                throw new AssertionError("Disabled logging must not inspect or copy the payload");
            }
        };

        StructuredLog.event(logger, Level.DEBUG, "search.debug", expensiveFields);

        assertTrue(capture.events.isEmpty());
    }

    @Test
    void rejectsAnEmptyEventName() {
        assertThrows(IllegalArgumentException.class,
                () -> StructuredLog.event(logger, Level.INFO, " \n", Map.of()));
        assertTrue(capture.events.isEmpty());
    }

    @Test
    void suppressesDuplicateClarinInfoMessagesButRetainsWarnings() {
        assertEquals(Level.WARN,
                context.getConfiguration().getLoggerConfig("eu.clarin.sru.server.SRUServer").getLevel());
        assertEquals(Level.INFO,
                context.getConfiguration().getLoggerConfig("de.ddb.labs.dzpfcs.endpoint.DzpEndpointSearchEngine").getLevel());
    }

    private JsonNode onlyJsonEvent() {
        assertEquals(1, capture.lines.size());
        String encoded = capture.lines.get(0);
        assertEquals(1, encoded.lines().count(), "User data must not create another physical log line");
        assertTrue(encoded.endsWith("\n"));
        return JSON.readTree(encoded);
    }

    private static final class CaptureAppender extends AbstractAppender {

        private final List<String> lines = new ArrayList<>();
        private final List<LogEvent> events = new ArrayList<>();

        private CaptureAppender(Layout<?> layout) {
            super("StructuredLogCapture", null, layout, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
            lines.add(new String(getLayout().toByteArray(event), StandardCharsets.UTF_8));
        }
    }
}
