package de.ddb.labs.dzpfcs.endpoint;

import eu.clarin.sru.server.SRUConfigException;
import eu.clarin.sru.server.SRUConstants;
import eu.clarin.sru.server.SRUDiagnostic;
import eu.clarin.sru.server.SRUException;
import eu.clarin.sru.server.SRUExplainResult;
import eu.clarin.sru.server.SRUQueryParserRegistry;
import eu.clarin.sru.server.SRURequest;
import eu.clarin.sru.server.SRUServerConfig;
import eu.clarin.sru.server.fcs.EndpointDescription;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.servlet.ServletContext;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamWriter;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the real CLARIN initialization/destruction sequence with independent
 * on-disk inventories. No backend request or servlet container is needed: the
 * observable contracts are corpus validation, Explain metadata and client cleanup.
 */
class EndpointLifecycleTest {

    private static final String FIRST_PID = "https://example.invalid/corpus/first";
    private static final String SECOND_PID = "https://example.invalid/corpus/second";
    private static final String INVENTORY_PARAM = "de.ddb.labs.dzpfcs.resourceInventoryURL";

    @TempDir
    Path temporaryDirectory;

    @Test
    void endpointInstancesRetainTheirOwnCorporaAndTheSingleInventoryUsedByExplain() throws Exception {
        AtomicInteger firstResourceLookups = new AtomicInteger();
        AtomicInteger secondResourceLookups = new AtomicInteger();
        Map<String, String> firstParams = parameters(FIRST_PID);
        Map<String, String> secondParams = parameters(SECOND_PID);
        SRUServerConfig firstConfig = configuration(firstParams);
        SRUServerConfig secondConfig = configuration(secondParams);

        try (TrackedEngine first = new TrackedEngine(); TrackedEngine second = new TrackedEngine()) {
            first.init(context(inventory("first.xml", FIRST_PID), firstResourceLookups), firstConfig,
                    new SRUQueryParserRegistry.Builder(), firstParams);
            EndpointDescription firstInventory = first.description();
            second.init(context(inventory("second.xml", SECOND_PID), secondResourceLookups), secondConfig,
                    new SRUQueryParserRegistry.Builder(), secondParams);

            assertEquals(List.of(FIRST_PID), first.parsePids(request(Map.of())));
            assertEquals(List.of(SECOND_PID), second.parsePids(request(Map.of())));
            assertSame(firstInventory, first.description());
            assertSame(first.loadedInventory, first.description());
            assertSame(second.loadedInventory, second.description());
            assertNotSame(first.description(), second.description());
            assertEquals(1, first.inventoryLoads);
            assertEquals(1, second.inventoryLoads);
            assertEquals(1, firstResourceLookups.get());
            assertEquals(1, secondResourceLookups.get());
            assertEquals(List.of(FIRST_PID), first.checkPids(List.of(FIRST_PID), (uri, details, message) -> {
                throw new AssertionError("The first endpoint must retain its own searchable corpus");
            }));
            List<SRUDiagnostic> diagnostics = new ArrayList<>();
            SRUException failure = assertThrows(SRUException.class,
                    () -> first.checkPids(List.of(SECOND_PID), (uri, details, message) ->
                            diagnostics.add(new SRUDiagnostic(uri, details, message))));
            assertEquals(SRUConstants.SRU_UNSUPPORTED_PARAMETER_VALUE, failure.getDiagnostic().getURI());
            assertEquals(SECOND_PID, diagnostics.get(0).getDetails());

            String firstExplain = endpointMetadata(first, firstConfig);
            String secondExplain = endpointMetadata(second, secondConfig);
            assertTrue(firstExplain.contains("pid=\"" + FIRST_PID + "\""));
            assertFalse(firstExplain.contains(SECOND_PID));
            assertTrue(secondExplain.contains("pid=\"" + SECOND_PID + "\""));
            assertFalse(secondExplain.contains(FIRST_PID));
            assertEquals(1, first.inventoryLoads, "Explain must reuse the initialized inventory");
        }
    }

    @Test
    void anExplicitFilesystemInventoryDoesNotRequireTheBundledServletResource() throws Exception {
        Map<String, String> params = parameters(FIRST_PID);
        params.put(INVENTORY_PARAM, inventory("configured.xml", FIRST_PID).toString());
        AtomicInteger resourceLookups = new AtomicInteger();

        try (TrackedEngine engine = new TrackedEngine()) {
            engine.init(context(null, resourceLookups), configuration(params),
                    new SRUQueryParserRegistry.Builder(), params);

            assertEquals(List.of(FIRST_PID), engine.parsePids(request(Map.of())));
            assertEquals(0, resourceLookups.get());
            assertEquals(1, engine.inventoryLoads);
            assertSame(engine.loadedInventory, engine.description());
        }
    }

    @Test
    void destroyingOneEndpointClosesItsOwnDispatcherWithoutClosingAnotherInstancesClient() throws Exception {
        Map<String, String> firstParams = parameters(FIRST_PID);
        Map<String, String> secondParams = parameters(SECOND_PID);
        try (TrackedEngine first = new TrackedEngine(); TrackedEngine second = new TrackedEngine()) {
            first.init(context(inventory("cleanup-first.xml", FIRST_PID), new AtomicInteger()),
                    configuration(firstParams), new SRUQueryParserRegistry.Builder(), firstParams);
            second.init(context(inventory("cleanup-second.xml", SECOND_PID), new AtomicInteger()),
                    configuration(secondParams), new SRUQueryParserRegistry.Builder(), secondParams);
            OkHttpClient firstClient = httpClient(first);
            OkHttpClient secondClient = httpClient(second);
            assertNotSame(firstClient, secondClient);
            assertNotSame(firstClient.dispatcher(), secondClient.dispatcher());
            assertNotSame(firstClient.connectionPool(), secondClient.connectionPool());

            first.close();

            assertTrue(firstClient.dispatcher().executorService().isShutdown());
            assertFalse(secondClient.dispatcher().executorService().isShutdown());
            assertEquals(List.of(SECOND_PID), second.parsePids(request(Map.of())));
        }
    }

    private Path inventory(String filename, String pid) throws Exception {
        String template = Files.readString(Path.of("src/main/webapp/WEB-INF/endpoint-description.xml"));
        Path path = temporaryDirectory.resolve(filename);
        Files.writeString(path, template.replace("https://www.deutsche-digitale-bibliothek.de/newspaper", pid));
        return path;
    }

    private static Map<String, String> parameters(String defaultPid) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put(SRUServerConfig.SRU_HOST, "example.invalid");
        params.put(SRUServerConfig.SRU_PORT, "443");
        params.put(SRUServerConfig.SRU_TRANSPORT, "https");
        params.put(SRUServerConfig.SRU_DATABASE, "test/sru");
        params.put("DEFAULT_RESOURCE_PID", defaultPid);
        return params;
    }

    private static SRUServerConfig configuration(Map<String, String> params) throws Exception {
        return SRUServerConfig.parse(params,
                Path.of("src/main/webapp/WEB-INF/sru-server-config.xml").toUri().toURL());
    }

    private static ServletContext context(Path inventory, AtomicInteger lookups) throws Exception {
        URL resource = inventory == null ? null : inventory.toUri().toURL();
        return (ServletContext) Proxy.newProxyInstance(ServletContext.class.getClassLoader(),
                new Class<?>[]{ServletContext.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "getResource" -> {
                        assertEquals("/WEB-INF/endpoint-description.xml", arguments[0]);
                        lookups.incrementAndGet();
                        yield resource;
                    }
                    case "toString" -> "InventoryServletContext";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static SRURequest request(Map<String, String> extraData) {
        return (SRURequest) Proxy.newProxyInstance(SRURequest.class.getClassLoader(),
                new Class<?>[]{SRURequest.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "getExtraRequestData" -> extraData.get((String) arguments[0]);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static String endpointMetadata(TrackedEngine engine, SRUServerConfig config) throws Exception {
        SRUExplainResult explain = engine.explain(config, request(Map.of("x-fcs-endpoint-description", "true")),
                (uri, details, message) -> {
                    throw new AssertionError("Unexpected Explain diagnostic: " + uri);
                });
        StringWriter text = new StringWriter();
        XMLStreamWriter xml = XMLOutputFactory.newFactory().createXMLStreamWriter(text);
        try {
            explain.writeExtraResponseData(xml);
            xml.flush();
            return text.toString();
        } finally {
            xml.close();
            explain.close();
        }
    }

    private static OkHttpClient httpClient(DzpEndpointSearchEngine engine) throws Exception {
        // No HTTP accessor is needed in the application API just for lifecycle assertions.
        Field field = DzpEndpointSearchEngine.class.getDeclaredField("httpClient");
        field.setAccessible(true);
        return (OkHttpClient) field.get(engine);
    }

    private static final class TrackedEngine extends DzpEndpointSearchEngine implements AutoCloseable {
        private int inventoryLoads;
        private EndpointDescription loadedInventory;
        private boolean closed;

        @Override
        protected EndpointDescription loadEndpointDescriptionFromURI(ServletContext context, Map<String, String> params)
                throws SRUConfigException {
            inventoryLoads++;
            loadedInventory = super.loadEndpointDescriptionFromURI(context, params);
            return loadedInventory;
        }

        private EndpointDescription description() {
            return endpointDescription;
        }

        @Override
        public void close() {
            if (!closed && endpointDescription != null) {
                closed = true;
                destroy();
            }
        }
    }
}
