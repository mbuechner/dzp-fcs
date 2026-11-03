package de.ddb.labs.dzpfcs.endpoint;

import de.ddb.labs.dzpfcs.backend.BackendHttpSettings;
import de.ddb.labs.dzpfcs.backend.BackendRequestExecutor;
import de.ddb.labs.dzpfcs.logging.RequestTelemetry;
import de.ddb.labs.dzpfcs.model.Results;
import de.ddb.labs.dzpfcs.model.ResultsEntry;
import eu.clarin.sru.server.SRUConstants;
import eu.clarin.sru.server.SRUException;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Protects the SRU-to-Solr paging and mapping contract independently of retry
 * policy. Each test uses a single-attempt executor and an in-memory HTTP response;
 * real socket deadlines are covered by the local Tomcat/backend runtime checks.
 */
class DzpBackendSearchTest {

    private static final String ENDPOINT = "https://example.invalid/2/search/index/newspaper-issues/select";
    private static final String PID = "https://example.invalid/corpus";
    private static final String FIRST_ID = "0123456789abcdef0123456789abcdef";
    private static final String SECOND_ID = "1123456789abcdef0123456789abcdef";
    private static final String LAST_ID = "2123456789abcdef0123456789abcdef";
    private static final String QUERY = "\"München + &rows=999 #fragment %20 {{rows}} {{start}}\"";
    @Test
    void fetchesAndMapsTheRequestedPageWithExactlyOneBackendCall() throws Exception {
        InMemoryResponse backend = new InMemoryResponse(200, """
                {
                  "response": {
                    "numFound": 53,
                    "start": 40,
                    "docs": [
                      {"id":"%s", "pagenumber":"7", "paper_title":"Zeitung"},
                      {"id":"%s", "pagenumber":"8"}
                    ]
                  },
                  "highlighting": {
                    "%s": {"plainpagefulltext":["Vor <Hit>Berlin</Hit> & Hamburg"]}
                  }
                }
                """.formatted(FIRST_ID, SECOND_ID, FIRST_ID));

        Results results = search(backend, 40, 25);

        assertEquals(1, backend.requests.size());
        Request request = backend.requests.get(0);
        assertEquals("GET", request.method());
        HttpUrl url = request.url();
        assertEquals("example.invalid", url.host());
        assertEquals("/2/search/index/newspaper-issues/select", url.encodedPath());
        assertEquals(List.of(QUERY), url.queryParameterValues("q"));
        assertEquals(List.of("25"), url.queryParameterValues("rows"));
        assertEquals(List.of("40"), url.queryParameterValues("start"));
        assertNull(url.fragment());
        assertEquals("plainpagefulltext", url.queryParameter("df"));
        assertEquals("id,pagenumber", url.queryParameter("fl"));
        assertNull(url.queryParameter("hl.bs.separator"));
        assertEquals("SENTENCE", url.queryParameter("hl.bs.type"));
        assertEquals("plainpagefulltext", url.queryParameter("hl.fl"));
        assertEquals("unified", url.queryParameter("hl.method"));
        assertEquals("<Hit>", url.queryParameter("hl.simple.pre"));
        assertEquals("</Hit>", url.queryParameter("hl.simple.post"));
        assertEquals("true", url.queryParameter("hl"));
        assertEquals(PID, results.getPid());
        assertEquals(QUERY, results.getQuery());
        assertEquals(53, results.getTotal());
        assertEquals(40, results.getOffset());
        List<ResultsEntry> entries = results.getResults();
        assertEquals(List.of(FIRST_ID, SECOND_ID), entries.stream().map(ResultsEntry::getId).toList());
        assertEquals("7", entries.get(0).getPagenumber());
        assertEquals(List.of("Vor <Hit>Berlin</Hit> & Hamburg"), entries.get(0).getPlainpagefulltext());
        assertTrue(entries.get(1).getPlainpagefulltext().isEmpty());
        assertTrue(backend.body.closed);
    }

    @Test
    void acceptsAnEmptyResultAtTheInitialPosition() throws Exception {
        InMemoryResponse backend = new InMemoryResponse(200, emptyPage(0, 0));

        Results results = search(backend, 0, 10);

        assertEquals(0, results.getTotal());
        assertEquals(0, results.getOffset());
        assertTrue(results.getResults().isEmpty());
        assertSingleCallAndClosedResponse(backend);
    }

    @Test
    void supportsCountOnlyRequestsWithoutASecondBackendCall() throws Exception {
        InMemoryResponse backend = new InMemoryResponse(200, emptyPage(53, 0));

        Results results = search(backend, 0, 0);

        assertEquals(53, results.getTotal());
        assertEquals(0, results.getOffset());
        assertTrue(results.getResults().isEmpty());
        assertEquals(List.of("0"), backend.requests.get(0).url().queryParameterValues("rows"));
        assertEquals("false", backend.requests.get(0).url().queryParameter("hl"));
        assertNull(backend.requests.get(0).url().queryParameter("hl.fl"));
        assertSingleCallAndClosedResponse(backend);
    }

    @ParameterizedTest
    @ValueSource(ints = {40, 53})
    void countOnlyRequestsKeepTheOriginalResultOffsetDespiteUsingZeroAtTheBackend(int originalOffset) throws Exception {
        InMemoryResponse backend = new InMemoryResponse(200, emptyPage(53, 0));

        Results results = search(backend, originalOffset, 0);

        assertEquals(53, results.getTotal());
        assertEquals(originalOffset, results.getOffset());
        assertTrue(results.getResults().isEmpty());
        assertEquals("0", backend.requests.get(0).url().queryParameter("start"));
        assertEquals("0", backend.requests.get(0).url().queryParameter("rows"));
        assertEquals("_docid_ asc", backend.requests.get(0).url().queryParameter("sort"));
        assertSingleCallAndClosedResponse(backend);
    }

    @Test
    void countOnlyRequestsStillRejectTheOriginalOutOfRangeOffset() {
        InMemoryResponse backend = new InMemoryResponse(200, emptyPage(53, 0));

        SRUException failure = assertThrows(SRUException.class, () -> search(backend, 54, 0));

        assertEquals(SRUConstants.SRU_FIRST_RECORD_POSITION_OUT_OF_RANGE, failure.getDiagnostic().getURI());
        assertEquals("0", backend.requests.get(0).url().queryParameter("start"));
        assertSingleCallAndClosedResponse(backend);
    }

    @Test
    void observesOptionalSolrTimingSeparatelyFromHttpLatency() throws Exception {
        InMemoryResponse backend = new InMemoryResponse(200, """
                {"responseHeader":{"status":0,"QTime":17,"partialResults":false},
                 "response":{"numFound":53,"start":0,"docs":[]}}
                """);
        try (RequestTelemetry.Scope scope = RequestTelemetry.open("solr-timing-test")) {
            Results results = search(backend, 40, 0);

            assertEquals(53, results.getTotal());
            assertEquals(40, results.getOffset());
            assertEquals(17L, scope.fields().get("solrQTimeMs"));
            assertEquals(false, scope.fields().get("solrPartialResults"));
            assertTrue(scope.fields().get("backendDurationMs") instanceof Number);
        }
        assertSingleCallAndClosedResponse(backend);
    }

    @Test
    void unusableOptionalTimingMetadataDoesNotBreakAValidSearchResponse() throws Exception {
        InMemoryResponse backend = new InMemoryResponse(200, """
                {"responseHeader":"unsupported metadata",
                 "response":{"numFound":0,"start":0,"docs":[]}}
                """);

        assertEquals(0, search(backend, 0, 0).getTotal());
        assertSingleCallAndClosedResponse(backend);
    }

    @Test
    void returnsTheFinalPartialPage() throws Exception {
        InMemoryResponse backend = new InMemoryResponse(200, """
                {"response":{"numFound":21,"start":20,"docs":[{"id":"%s","pagenumber":"21"}]}}
                """.formatted(LAST_ID));

        Results results = search(backend, 20, 10);

        assertEquals(21, results.getTotal());
        assertEquals(20, results.getOffset());
        assertEquals(List.of(LAST_ID), results.getResults().stream().map(ResultsEntry::getId).toList());
        assertSingleCallAndClosedResponse(backend);
    }

    @Test
    void keepsTheExistingBoundaryWhereStartEqualsTotalAllowed() throws Exception {
        InMemoryResponse backend = new InMemoryResponse(200, emptyPage(21, 21));

        Results results = search(backend, 21, 10);

        assertEquals(21, results.getTotal());
        assertEquals(21, results.getOffset());
        assertTrue(results.getResults().isEmpty());
        assertSingleCallAndClosedResponse(backend);
    }

    @Test
    void rejectsOutOfRangePositionsBeforeMappingMalformedDocuments() {
        InMemoryResponse backend = new InMemoryResponse(200, """
                {"response":{"numFound":2,"docs":[{"id":"invalid","pagenumber":{"cannot":"map to String"}}]}}
                """);

        SRUException failure = assertThrows(SRUException.class, () -> search(backend, 3, 10));

        assertEquals(SRUConstants.SRU_FIRST_RECORD_POSITION_OUT_OF_RANGE, failure.getDiagnostic().getURI());
        assertSingleCallAndClosedResponse(backend);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "{\"response\":",
        "null",
        "[]",
        "{}",
        "{\"response\":null}",
        "{\"response\":[]}",
        "{\"response\":{\"docs\":[]}}",
        "{\"response\":{\"numFound\":null,\"docs\":[]}}",
        "{\"response\":{\"numFound\":\"2\",\"docs\":[]}}",
        "{\"response\":{\"numFound\":true,\"docs\":[]}}",
        "{\"response\":{\"numFound\":-1,\"docs\":[]}}",
        "{\"response\":{\"numFound\":1.5,\"docs\":[]}}",
        "{\"response\":{\"numFound\":2147483648,\"docs\":[]}}",
        "{\"response\":{\"numFound\":1}}",
        "{\"response\":{\"numFound\":1,\"docs\":null}}",
        "{\"response\":{\"numFound\":1,\"docs\":{\"id\":\"record\"}}}",
        "{\"response\":{\"numFound\":1,\"docs\":[null]}}",
        "{\"response\":{\"numFound\":1,\"docs\":[7]}}",
        "{\"response\":{\"numFound\":1,\"docs\":[{}]}}",
        "{\"response\":{\"numFound\":1,\"docs\":[{\"id\":null}]}}",
        "{\"response\":{\"numFound\":1,\"docs\":[{\"id\":42}]}}",
        "{\"response\":{\"numFound\":1,\"docs\":[{\"id\":\" \"}]}}",
        "{\"response\":{\"numFound\":1,\"docs\":[{\"id\":\"record\",\"pagenumber\":{\"bad\":\"type\"}}]}}",
        "{\"response\":{\"numFound\":1,\"docs\":[{\"id\":\"record\"}]},\"highlighting\":\"invalid\"}",
        "{\"response\":{\"numFound\":1,\"docs\":[{\"id\":\"record\"}]},\"highlighting\":{\"record\":[]}}",
        "{\"response\":{\"numFound\":1,\"docs\":[{\"id\":\"record\"}]},\"highlighting\":{\"record\":{\"plainpagefulltext\":\"invalid\"}}}",
        "{\"response\":{\"numFound\":1,\"docs\":[{\"id\":\"record\"}]},\"highlighting\":{\"record\":{\"plainpagefulltext\":[null]}}}",
        "{\"response\":{\"numFound\":1,\"docs\":[{\"id\":\"record\"}]},\"highlighting\":{\"record\":{\"plainpagefulltext\":[42]}}}"
    })
    void convertsMalformedJsonAndInvalidSolrStructuresToSystemDiagnosticsWithoutRetrying(String json) {
        InMemoryResponse backend = new InMemoryResponse(200, json.replace("\"record\"", "\"" + FIRST_ID + "\""));

        SRUException failure = assertThrows(SRUException.class, () -> search(backend, 0, 10));

        assertEquals(SRUConstants.SRU_GENERAL_SYSTEM_ERROR, failure.getDiagnostic().getURI());
        assertEquals("Invalid DDB-API search response.", failure.getMessage());
        assertTrue(failure.getCause() instanceof RuntimeException);
        assertSingleCallAndClosedResponse(backend);
    }

    @Test
    void rejectsShortDocumentIdentifiersBeforeTheyCanReachRecordSerialization() {
        InMemoryResponse backend = new InMemoryResponse(200, """
                {"response":{"numFound":1,"docs":[{"id":"short","pagenumber":"1"}]},
                 "highlighting":{"short":{"plainpagefulltext":["Vor <Hit>Berlin</Hit> danach"]}}}
                """);

        SRUException failure = assertThrows(SRUException.class, () -> search(backend, 0, 1));

        assertEquals(SRUConstants.SRU_GENERAL_SYSTEM_ERROR, failure.getDiagnostic().getURI());
        assertEquals("Invalid DDB-API search response.", failure.getMessage());
        assertSingleCallAndClosedResponse(backend);
    }

    @Test
    void acceptsTheLargestCountRepresentableByTheSruFrameworkWithoutOverflow() throws Exception {
        InMemoryResponse backend = new InMemoryResponse(200, emptyPage(Integer.MAX_VALUE, 0));

        Results results = search(backend, 0, 0);

        assertEquals(Integer.MAX_VALUE, results.getTotal());
        assertTrue(results.getResults().isEmpty());
        assertSingleCallAndClosedResponse(backend);
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502})
    void reportsHttpErrorsAsGeneralSystemDiagnosticsAndClosesTheResponse(int status) {
        InMemoryResponse backend = new InMemoryResponse(status, "not JSON");

        SRUException failure = assertThrows(SRUException.class, () -> search(backend, 0, 10));

        assertSafeBackendFailure(failure);
        assertTrue(failure.getCause().getMessage().contains("Response code of DDB-API is " + status));
        assertTrue(failure.getCause().getMessage().contains(ENDPOINT));
        assertSingleCallAndClosedResponse(backend);
    }

    @Test
    void reportsTransportFailuresAsGeneralSystemDiagnostics() {
        IOException transportFailure = new IOException("Transport failed at https://private.invalid/select?apiKey=test-token");
        InMemoryResponse backend = new InMemoryResponse(transportFailure);

        SRUException failure = assertThrows(SRUException.class, () -> search(backend, 0, 10));

        assertSafeBackendFailure(failure);
        assertSame(transportFailure, failure.getCause());
        assertEquals(1, backend.requests.size());
    }

    @Test
    void preservesSystemDiagnosticsForUnexpectedHttpClientFailures() {
        NumberFormatException nativeFailure = new NumberFormatException("simulated HTTP header parsing failure");
        BackendRequestExecutor executor = singleAttemptExecutor(chain -> {
            throw nativeFailure;
        });

        SRUException failure = assertThrows(SRUException.class,
                () -> DzpEndpointSearchEngine.searchBackend(executor, ENDPOINT, PID, QUERY, 0, 10));

        assertSafeBackendFailure(failure);
        assertSame(nativeFailure, failure.getCause());
    }

    @Test
    void closesTheResponseWhenReadingItsBodyFails() {
        IOException readFailure = new IOException("simulated response body failure");
        InMemoryResponse backend = new InMemoryResponse(200, new TrackedResponseBody(readFailure));

        SRUException failure = assertThrows(SRUException.class, () -> search(backend, 0, 10));

        assertSafeBackendFailure(failure);
        assertSame(readFailure, failure.getCause());
        assertSingleCallAndClosedResponse(backend);
    }

    /** Public diagnostics stay stable even if a private HTTP failure contains URLs or credentials. */
    private static void assertSafeBackendFailure(SRUException failure) {
        assertEquals(SRUConstants.SRU_GENERAL_SYSTEM_ERROR, failure.getDiagnostic().getURI());
        assertEquals("DDB-API search request failed.", failure.getMessage());
        assertEquals("DDB-API search request failed.", failure.getDiagnostic().getMessage());
    }

    private static Results search(InMemoryResponse backend, int startRecord, int maximumRecords) throws SRUException {
        return DzpEndpointSearchEngine.searchBackend(singleAttemptExecutor(backend), ENDPOINT, PID, QUERY,
                startRecord, maximumRecords);
    }

    /** Disable outer retries here so request-count assertions test mapping, not resilience. */
    private static BackendRequestExecutor singleAttemptExecutor(Interceptor backend) {
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(backend)
                .build();
        BackendHttpSettings singleAttempt = new BackendHttpSettings(Duration.ofSeconds(10),
                Duration.ofSeconds(50), Duration.ofSeconds(50), 1, 16, Duration.ZERO, Duration.ZERO);
        return new BackendRequestExecutor(client, singleAttempt);
    }

    private static String emptyPage(int total, int start) {
        return "{\"response\":{\"numFound\":" + total + ",\"start\":" + start + ",\"docs\":[]}}";
    }

    private static void assertSingleCallAndClosedResponse(InMemoryResponse backend) {
        assertEquals(1, backend.requests.size());
        assertTrue(backend.body.closed);
    }

    private static final class InMemoryResponse implements Interceptor {
        private final List<Request> requests = new ArrayList<>();
        private final int status;
        private final TrackedResponseBody body;
        private final IOException transportFailure;

        private InMemoryResponse(int status, String json) {
            this(status, new TrackedResponseBody(json));
        }

        private InMemoryResponse(int status, TrackedResponseBody body) {
            this.status = status;
            this.body = body;
            this.transportFailure = null;
        }

        private InMemoryResponse(IOException transportFailure) {
            this.status = 0;
            this.body = null;
            this.transportFailure = transportFailure;
        }

        @Override
        public Response intercept(Chain chain) throws IOException {
            requests.add(chain.request());
            if (transportFailure != null) {
                throw transportFailure;
            }
            return new Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(status)
                    .message(status == 200 ? "OK" : "Backend error")
                    .body(body)
                    .build();
        }
    }

    /** Makes response ownership observable, including cleanup after a failed body read. */
    private static final class TrackedResponseBody extends ResponseBody {
        private final BufferedSource source;
        private boolean closed;

        private TrackedResponseBody(String json) {
            this.source = new Buffer().writeUtf8(json);
        }

        private TrackedResponseBody(IOException readFailure) {
            this.source = Okio.buffer(new ForwardingSource(new Buffer()) {
                @Override
                public long read(Buffer sink, long byteCount) throws IOException {
                    throw readFailure;
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
            closed = true;
            super.close();
        }
    }
}
