package de.ddb.labs.dzpfcs.endpoint;

import eu.clarin.sru.server.SRUConstants;
import eu.clarin.sru.server.CQLQueryParser;
import eu.clarin.sru.server.SRUDiagnostic;
import eu.clarin.sru.server.SRUException;
import eu.clarin.sru.server.SRURequest;
import eu.clarin.sru.server.SRUQuery;
import eu.clarin.sru.server.SRUVersion;
import eu.clarin.sru.server.fcs.Constants;
import eu.clarin.sru.server.fcs.EndpointDescription;
import eu.clarin.sru.server.fcs.utils.SimpleEndpointDescriptionParser;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import okhttp3.HttpUrl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DzpEndpointSearchEngineTest {

    private static final String ENDPOINT = "https://example.invalid/solr/select";
    private static final String RESOURCE_PID = "https://www.deutsche-digitale-bibliothek.de/newspaper";
    @ParameterizedTest
    @CsvSource({"1,0", "25,40"})
    void preservesSearchTextAndPaginationAsSeparateParameters(int rows, int start) {
        String query = "\"München a+b &rows=999 #fragment %20 {{query}} {{rows}} {{start}}\"";

        HttpUrl url = DzpEndpointSearchEngine.buildSearchUrl(ENDPOINT, query, rows, start);

        assertEquals(query, url.queryParameter("q"));
        assertEquals(List.of(query), url.queryParameterValues("q"));
        assertEquals(List.of(Integer.toString(rows)), url.queryParameterValues("rows"));
        assertEquals(List.of(Integer.toString(start)), url.queryParameterValues("start"));
        assertNull(url.fragment());
        assertEquals("example.invalid", url.host());
        assertEquals("/solr/select", url.encodedPath());
        assertEquals("<Hit>", url.queryParameter("hl.simple.pre"));
        assertEquals("</Hit>", url.queryParameter("hl.simple.post"));
        assertEquals("plainpagefulltext", url.queryParameter("df"));
        assertEquals("id,pagenumber", url.queryParameter("fl"));
        assertEquals("true", url.queryParameter("hl"));
        assertEquals("SENTENCE", url.queryParameter("hl.bs.type"));
        assertNull(url.queryParameter("hl.bs.separator"));
        assertNull(url.queryParameter("hl.offsetSource"));
        assertNull(url.queryParameter("sort"));
    }

    @ParameterizedTest
    @ValueSource(ints = {40, Integer.MAX_VALUE})
    void countOnlyRequestsDisableHighlightingAndAvoidCollectingAnUnusedDeepPage(int requestedOffset) {
        String query = "\"Berlin\" AND \"Hamburg\"";

        HttpUrl url = DzpEndpointSearchEngine.buildSearchUrl(ENDPOINT, query, 0, requestedOffset);

        assertEquals(query, url.queryParameter("q"));
        assertEquals("plainpagefulltext", url.queryParameter("df"));
        assertEquals("0", url.queryParameter("rows"));
        assertEquals("0", url.queryParameter("start"));
        assertEquals("id,pagenumber", url.queryParameter("fl"));
        assertEquals(List.of("false"), url.queryParameterValues("hl"));
        assertEquals("_docid_ asc", url.queryParameter("sort"));
        assertTrue(url.queryParameterNames().stream().noneMatch(name -> name.startsWith("hl.")));
    }

    @Test
    void countOnlyRequestsOverrideDuplicateEndpointHighlightingFlags() {
        HttpUrl url = DzpEndpointSearchEngine.buildSearchUrl(
                ENDPOINT + "?hl=true&hl=true&sort=score%20desc&custom=keep", "\"Berlin\"", 0, 0);

        assertEquals(List.of("false"), url.queryParameterValues("hl"));
        assertEquals(List.of("_docid_ asc"), url.queryParameterValues("sort"));
        assertEquals("keep", url.queryParameter("custom"));
    }

    @Test
    void preservesEndpointOptionsAndReplacesExistingSearchParameters() {
        String endpoint = ENDPOINT + "?custom=keep%2Bme&q=old&rows=99&rows=98&start=90&sort=score%20desc";

        HttpUrl url = DzpEndpointSearchEngine.buildSearchUrl(endpoint, "\"Berlin\"", 10, 20);

        assertEquals("keep+me", url.queryParameter("custom"));
        assertEquals(List.of("\"Berlin\""), url.queryParameterValues("q"));
        assertEquals(List.of("10"), url.queryParameterValues("rows"));
        assertEquals(List.of("20"), url.queryParameterValues("start"));
        assertEquals(List.of("score desc"), url.queryParameterValues("sort"));
    }

    @Test
    void requestsCompactJsonWithoutParameterEchoWhilePreservingDiagnosticHeaders() {
        String endpoint = ENDPOINT + "?wt=xml&wt=json&indent=true&echoParams=all&omitHeader=true&custom=keep";

        HttpUrl url = DzpEndpointSearchEngine.buildSearchUrl(endpoint, "\"Berlin\"", 10, 20);

        assertEquals(List.of("json"), url.queryParameterValues("wt"));
        assertEquals(List.of("false"), url.queryParameterValues("indent"));
        assertEquals(List.of("none"), url.queryParameterValues("echoParams"));
        assertEquals(List.of("false"), url.queryParameterValues("omitHeader"));
        assertEquals("keep", url.queryParameter("custom"));
        assertEquals("20", url.queryParameter("start"));
    }

    @Test
    void resolvesRequestedViewsOnTheLeafResourceInsteadOfItsAbsentChildren() throws Exception {
        EndpointDescription description = endpointDescription();
        assertNull(description.getResourceList(RESOURCE_PID));
        List<SRUDiagnostic> diagnostics = new ArrayList<>();

        List<String> views = parseViews(description, requestDataViews("hits"), RESOURCE_PID, diagnostics);

        assertEquals(List.of("hits"), views);
        assertTrue(diagnostics.isEmpty());
    }

    @Test
    void returnsTheSupportedViewsAndOneDataViewDiagnosticForEachUnknownIdentifier() throws Exception {
        List<SRUDiagnostic> diagnostics = new ArrayList<>();

        List<String> views = parseViews(endpointDescription(), requestDataViews("missing,hits,other"),
                RESOURCE_PID, diagnostics);

        assertEquals(List.of("hits"), views);
        assertEquals(List.of(Constants.FCS_DIAGNOSTIC_REQUESTED_DATA_VIEW_INVALID,
                Constants.FCS_DIAGNOSTIC_REQUESTED_DATA_VIEW_INVALID),
                diagnostics.stream().map(SRUDiagnostic::getURI).toList());
        assertEquals(List.of("missing", "other"), diagnostics.stream().map(SRUDiagnostic::getDetails).toList());
    }

    @Test
    void missingOrEmptyViewSelectionKeepsDefaultDeliveryWithoutLookingUpResources() throws Exception {
        List<SRUDiagnostic> diagnostics = new ArrayList<>();

        assertTrue(parseViews(null, requestExtraData(Map.of()), RESOURCE_PID, diagnostics).isEmpty());
        assertTrue(parseViews(null, requestDataViews(""), RESOURCE_PID, diagnostics).isEmpty());
        assertTrue(parseViews(null, null, RESOURCE_PID, diagnostics).isEmpty());
        assertTrue(diagnostics.isEmpty());
    }

    @Test
    void unknownResourceProducesAPersistentIdentifierDiagnosticInsteadOfNullDereference() throws Exception {
        String unknownPid = "https://example.invalid/unknown-resource";
        List<SRUDiagnostic> diagnostics = new ArrayList<>();

        SRUException failure = assertThrows(SRUException.class, () -> parseViews(endpointDescription(),
                requestDataViews("hits"), unknownPid, diagnostics));

        assertEquals(Constants.FCS_DIAGNOSTIC_PERSISTENT_IDENTIFIER_INVALID, failure.getDiagnostic().getURI());
        assertEquals(unknownPid, failure.getDiagnostic().getDetails());
        assertTrue(diagnostics.isEmpty());
    }

    @Test
    void unavailableEndpointMetadataProducesASystemDiagnostic() {
        SRUException failure = assertThrows(SRUException.class, () -> parseViews(null,
                requestDataViews("hits"), RESOURCE_PID, new ArrayList<>()));

        assertEquals(SRUConstants.SRU_GENERAL_SYSTEM_ERROR, failure.getDiagnostic().getURI());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "fcs.words = \"Berlin\"|info:srw/diagnostic/1/16",
        "cql.serverChoice any \"Berlin Hamburg\"|info:srw/diagnostic/1/19",
        "cql.serverChoice all \"Berlin Hamburg\"|info:srw/diagnostic/1/19",
        "cql.serverChoice =/relevant \"Berlin\"|info:srw/diagnostic/1/20"
    })
    void preservesSpecificConverterDiagnosticsAtTheSearchEngineBoundary(String query, String diagnostic) {
        List<SRUDiagnostic> parserDiagnostics = new ArrayList<>();
        SRUQuery<?> parsed = new CQLQueryParser().parseQuery(SRUVersion.VERSION_1_2, Map.of("query", query),
                (uri, details, message) -> parserDiagnostics.add(new SRUDiagnostic(uri, details, message)));
        assertTrue(parserDiagnostics.isEmpty());
        SRURequest request = (SRURequest) Proxy.newProxyInstance(SRURequest.class.getClassLoader(),
                new Class<?>[]{SRURequest.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "isQueryType" -> Constants.FCS_QUERY_TYPE_CQL.equals(args[0]);
                    case "getQuery" -> parsed;
                    default -> throw new UnsupportedOperationException(method.getName());
                });

        SRUException failure = assertThrows(SRUException.class,
                () -> new DzpEndpointSearchEngine().parseQuery(request));

        assertEquals(diagnostic, failure.getDiagnostic().getURI());
    }

    private static EndpointDescription endpointDescription() throws Exception {
        return SimpleEndpointDescriptionParser.parse(
                Path.of("src/main/webapp/WEB-INF/endpoint-description.xml").toUri().toURL());
    }

    private static SRURequest requestDataViews(String value) {
        return requestExtraData(Map.of(DzpConstants.X_FCS_DATAVIEWS_KEY, value));
    }

    private static SRURequest requestExtraData(Map<String, String> values) {
        return (SRURequest) Proxy.newProxyInstance(SRURequest.class.getClassLoader(),
                new Class<?>[]{SRURequest.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getExtraRequestDataNames" -> new ArrayList<>(values.keySet());
                    case "getExtraRequestData" -> values.get((String) args[0]);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static List<String> parseViews(EndpointDescription description, SRURequest request, String pid,
            List<SRUDiagnostic> diagnostics) throws Exception {
        return new MetadataEngine(description).parseDataViews(request,
                (uri, details, message) -> diagnostics.add(new SRUDiagnostic(uri, details, message)), pid);
    }

    private static final class MetadataEngine extends DzpEndpointSearchEngine {
        private MetadataEngine(EndpointDescription description) {
            endpointDescription = description;
        }
    }


}
