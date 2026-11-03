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
package de.ddb.labs.dzpfcs.endpoint;

import de.ddb.labs.dzpfcs.backend.BackendHttpSettings;
import de.ddb.labs.dzpfcs.backend.BackendRequestExecutor;
import de.ddb.labs.dzpfcs.backend.DdbApiIdentificationInterceptor;
import de.ddb.labs.dzpfcs.backend.SolrResponseParser;
import de.ddb.labs.dzpfcs.logging.RequestTelemetry;
import de.ddb.labs.dzpfcs.logging.StructuredLog;
import de.ddb.labs.dzpfcs.model.Results;
import de.ddb.labs.dzpfcs.query.CQLToSolrConverter;
import eu.clarin.sru.server.CQLQueryParser;
import eu.clarin.sru.server.SRUConfigException;
import eu.clarin.sru.server.SRUConstants;
import eu.clarin.sru.server.SRUDiagnosticList;
import eu.clarin.sru.server.SRUException;
import eu.clarin.sru.server.SRUQueryParserRegistry;
import eu.clarin.sru.server.SRURequest;
import eu.clarin.sru.server.SRUSearchResultSet;
import eu.clarin.sru.server.SRUServerConfig;
import eu.clarin.sru.server.fcs.Constants;
import eu.clarin.sru.server.fcs.DataView;
import eu.clarin.sru.server.fcs.EndpointDescription;
import eu.clarin.sru.server.fcs.ResourceInfo;
import eu.clarin.sru.server.fcs.SimpleEndpointSearchEngineBase;
import eu.clarin.sru.server.fcs.utils.SimpleEndpointDescriptionParser;
import io.github.cdimascio.dotenv.Dotenv;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import javax.servlet.ServletContext;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Bridges CLARIN's SRU lifecycle to one paginated Solr backend operation.
 *
 * <p>The servlet initializes each instance before publishing it to request
 * threads. Corpus metadata and the shared HTTP client then remain unchanged;
 * queries, result pages and log fields are local to each invocation. Separate
 * endpoint instances never share corpus configuration or mutable JSON defaults.
 * The inherited endpoint description is also used for Explain responses.</p>
 */
public class DzpEndpointSearchEngine extends SimpleEndpointSearchEngineBase {

    private static final Logger LOGGER = LogManager.getLogger(DzpEndpointSearchEngine.class);
    private static final String RESOURCE_INVENTORY_URL = "de.ddb.labs.dzpfcs.resourceInventoryURL";

    private final Dotenv dotenv = Dotenv.load();
    private final String solrEndpoint = dotenv.get("DZP_FCS_SOLR_ENDPOINT");
    private Set<String> resourcePids = Set.of();
    private String defaultCorpusId;
    private OkHttpClient httpClient;
    private BackendRequestExecutor backend;

    /** Load the bundled inventory or an explicitly configured filesystem path. */
    protected EndpointDescription loadEndpointDescriptionFromURI(ServletContext context, Map<String, String> params)
            throws SRUConfigException {
        try {
            String path = params.get(RESOURCE_INVENTORY_URL);
            URL inventory = path == null || path.isEmpty()
                    ? context.getResource("/WEB-INF/endpoint-description.xml") : new File(path).toURI().toURL();
            if (inventory == null) {
                throw new SRUConfigException("Endpoint description resource is missing.");
            }
            return SimpleEndpointDescriptionParser.parse(inventory);
        } catch (MalformedURLException e) {
            throw new SRUConfigException("Invalid endpoint description location.", e);
        }
    }

    /** Only root resources are searchable; subresources are not separate corpora here. */
    protected List<String> getCollectionsFromEndpointDescription(EndpointDescription description) throws SRUException {
        List<ResourceInfo> roots = description.getResourceList(EndpointDescription.PID_ROOT);
        return roots == null ? List.of() : roots.stream().map(ResourceInfo::getPid).toList();
    }

    /**
     * Reuse the inventory loaded by doInit. The base class calls this hook once
     * more afterwards, so Explain and searching use the same parsed object.
     */
    @Override
    protected EndpointDescription createEndpointDescription(ServletContext context, SRUServerConfig config,
            Map<String, String> params) throws SRUConfigException {
        return endpointDescription == null ? loadEndpointDescriptionFromURI(context, params) : endpointDescription;
    }

    /** Validate configuration once and create the per-instance connection pool and call gate. */
    @Override
    protected void doInit(ServletContext context, SRUServerConfig config,
            SRUQueryParserRegistry.Builder queryParsersBuilder, Map<String, String> params) throws SRUConfigException {
        endpointDescription = createEndpointDescription(context, config, params);
        try {
            resourcePids = Set.copyOf(getCollectionsFromEndpointDescription(endpointDescription));
        } catch (SRUException e) {
            throw new SRUConfigException("Unable to read the endpoint's root resources.", e);
        }
        defaultCorpusId = params.get("DEFAULT_RESOURCE_PID");
        if (defaultCorpusId == null || !resourcePids.contains(defaultCorpusId)) {
            throw new SRUConfigException("DEFAULT_RESOURCE_PID must identify a root resource in the endpoint description.");
        }
        if (solrEndpoint == null || solrEndpoint.isBlank()) {
            throw new SRUConfigException("DZP_FCS_SOLR_ENDPOINT must be configured.");
        }

        String version = loadApplicationVersion();
        final BackendHttpSettings settings;
        try {
            HttpUrl.get(solrEndpoint);
            settings = BackendHttpSettings.from(dotenv::get);
            httpClient = new OkHttpClient.Builder()
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .retryOnConnectionFailure(true)
                    .connectTimeout(settings.connectTimeout())
                    .readTimeout(settings.readTimeout())
                    .addInterceptor(new DdbApiIdentificationInterceptor(
                            dotenv.get("DZP_FCS_CLIENT_ID", "dzp-fcs"), version,
                            dotenv.get("DZP_FCS_CONTACT_EMAIL", "m.buechner@dnb.de"),
                            dotenv.get("DZP_FCS_CONTACT_URL", "https://github.com/mbuechner/dzp-fcs")))
                    .build();
        } catch (IllegalArgumentException e) {
            throw new SRUConfigException("Invalid DDB API configuration: " + e.getMessage(), e);
        }
        backend = new BackendRequestExecutor(httpClient, settings);
        LOGGER.info("Search endpoint initialized: default corpus={}, root resources={}", defaultCorpusId, resourcePids);
        LOGGER.info("Backend HTTP settings: request budget={}s, connect timeout={}s, read timeout={}s, "
                + "max attempts={}, max concurrent requests={}, retry delay={}ms, max retry delay={}ms",
                settings.requestTimeout().toSeconds(), settings.connectTimeout().toSeconds(),
                settings.readTimeout().toSeconds(), settings.maxAttempts(), settings.maxConcurrentRequests(),
                settings.retryDelay().toMillis(), settings.maxRetryDelay().toMillis());
    }

    /** Release this endpoint's client resources when the servlet is stopped or redeployed. */
    @Override
    protected void doDestroy() {
        if (httpClient != null) {
            httpClient.dispatcher().cancelAll();
            httpClient.dispatcher().executorService().shutdown();
            httpClient.connectionPool().evictAll();
        }
    }

    /** Maven filters this resource; API identification follows the actual application version. */
    private static String loadApplicationVersion() throws SRUConfigException {
        try (InputStream input = DzpEndpointSearchEngine.class.getResourceAsStream("/dzp-fcs.properties")) {
            if (input == null) {
                throw new SRUConfigException("Missing application version resource 'dzp-fcs.properties'.");
            }
            Properties application = new Properties();
            application.load(input);
            String version = application.getProperty("application.version");
            if (version == null || version.isBlank()) {
                throw new SRUConfigException("Missing 'application.version' in dzp-fcs.properties.");
            }
            return version;
        } catch (IOException e) {
            throw new SRUConfigException("Unable to load the application version.", e);
        }
    }

    /** Translate, validate and fetch one page; request telemetry includes failures and JSON decoding. */
    @Override
    public SRUSearchResultSet search(SRUServerConfig config, SRURequest request, SRUDiagnosticList diagnostics) throws SRUException {
        final long startedAt = System.nanoTime();
        final Map<String, Object> logFields = new LinkedHashMap<>();
        Level logLevel = Level.INFO;
        Throwable unexpectedFailure = null;
        try {
            logFields.put("query", request.getQuery().getRawQuery());
            logFields.put("queryType", request.getQueryType());
            logFields.put("startRecord", request.getStartRecord());
            logFields.put("maximumRecords", request.getMaximumRecords());
            final String solrQuery = parseQuery(request);

            final String pid = checkPid(checkPids(parsePids(request), diagnostics));
            parseDataViews(request, diagnostics, pid);
            final int startRecord = ((request.getStartRecord() < 1) ? 1 : request.getStartRecord()) - 1;
            final int maximumRecords = request.getMaximumRecords();

            final Results results = searchBackend(backend, solrEndpoint, pid, solrQuery, startRecord, maximumRecords);
            final DzpSRUSearchResultSet resultSet = new DzpSRUSearchResultSet(request, diagnostics, results);
            final int returnedRecords = results.getResults().size();
            RequestTelemetry.searchReturned(results.getTotal(), returnedRecords);
            logFields.put("outcome", "returned");
            logFields.put("numFound", results.getTotal());
            logFields.put("returnedRecords", returnedRecords);
            logFields.put("corpus", pid);
            return resultSet;
        } catch (SRUException e) {
            String diagnostic = e.getDiagnostic().getURI();
            RequestTelemetry.searchFailed("diagnostic", diagnostic);
            logFields.put("outcome", "diagnostic");
            logFields.put("sruDiagnostic", diagnostic);
            logFields.put("errorClass", e.getClass().getName());
            logLevel = Level.WARN;
            throw e;
        } catch (RuntimeException | Error e) {
            RequestTelemetry.searchFailed("exception", null);
            logFields.put("outcome", "exception");
            logFields.put("errorClass", e.getClass().getName());
            logLevel = Level.ERROR;
            unexpectedFailure = e;
            throw e;
        } finally {
            long duration = System.nanoTime() - startedAt;
            RequestTelemetry.searchFinished(duration);
            logFields.put("searchDurationMs", RequestTelemetry.millis(duration));
            StructuredLog.event(LOGGER, logLevel, "search.completed", logFields, unexpectedFailure);
        }
    }

    /**
     * Fetch and decode one response. Retries belong to the HTTP executor only;
     * the parser preserves the caller's zero-based offset and range diagnostics.
     */
    static Results searchBackend(BackendRequestExecutor backend, String endpoint, String pid, String query,
            int startRecord, int maximumRecords) throws SRUException {
        Request request = new Request.Builder().url(buildSearchUrl(endpoint, query, maximumRecords, startRecord)).build();
        final String json;
        try {
            json = backend.execute(request);
        } catch (Exception e) {
            // HTTP exception messages can contain backend URLs or transport details.
            // Keep the cause for server-side diagnosis without publishing it to clients.
            throw new SRUException(SRUConstants.SRU_GENERAL_SYSTEM_ERROR,
                    "DDB-API search request failed.", e);
        }
        return SolrResponseParser.parse(json, pid, query, startRecord);
    }

    /**
     * Build the Solr search/highlighting parameters using URL encoding.
     * Query text is a parameter value, so characters such as {@code &} cannot
     * inject additional paging or highlighting parameters.
     *
     * <p>Only {@code id} and {@code pagenumber} are needed for the SRU output;
     * the stored full text is read by Solr's highlighter, not transferred as a
     * document field. Count-only requests disable highlighting and send a zero
     * backend offset: with {@code rows=0}, a positive {@code start} can still make
     * Solr collect an unnecessarily deep top-N result set. The original offset
     * remains with the caller for SRU range checks and result metadata.
     * Only this empty-page request uses Solr's built-in document-ID ordering to
     * avoid relevance scoring. Regular pages retain their existing ranking.
     * Sentence boundaries and the existing hit markers are preserved for pages.
     * {@code hl.bs.separator} is deliberately absent: it only applies to the
     * different {@code SEPARATOR} boundary mode.</p>
     *
     * <p>The response uses compact JSON without a redundant parameter echo.
     * Formatting whitespace is removed only outside string values, preserving
     * snippet contents. The response header remains available for query timing
     * and partial-result diagnostics.</p>
     *
     * <p>Do not force {@code hl.offsetSource=POSTINGS}: the supplied backend schema
     * has no stored offsets for {@code plainpagefulltext}. Unified highlighting
     * chooses the available source automatically; faster indexed offsets require
     * a backend schema change and a complete reindex.</p>
     *
     * @param endpoint Solr select endpoint
     * @param query translated Solr query, before URL encoding
     * @param rows requested page size
     * @param start zero-based SRU offset; sent as zero for count-only requests
     * @return encoded URL with one value per configured search parameter
     */
    static HttpUrl buildSearchUrl(String endpoint, String query, int rows, int start) {
        final HttpUrl.Builder url = HttpUrl.get(endpoint).newBuilder()
                .setQueryParameter("df", "plainpagefulltext")
                .setQueryParameter("fl", "id,pagenumber")
                .setQueryParameter("q", query)
                .setQueryParameter("rows", Integer.toString(rows))
                .setQueryParameter("start", Integer.toString(rows == 0 ? 0 : start))
                .setQueryParameter("wt", "json")
                .setQueryParameter("indent", "false")
                .setQueryParameter("echoParams", "none")
                .setQueryParameter("omitHeader", "false")
                .setQueryParameter("hl", Boolean.toString(rows > 0));

        if (rows == 0) {
            // No documents are returned or merged, including in distributed search.
            // _docid_ is a built-in sort; it needs no schema field or DocValues.
            url.setQueryParameter("sort", "_docid_ asc");
        } else if (rows > 0) {
            url.setQueryParameter("hl.bs.type", "SENTENCE")
                    .setQueryParameter("hl.fl", "plainpagefulltext")
                    .setQueryParameter("hl.method", "unified")
                    .setQueryParameter("hl.simple.post", "</Hit>")
                    .setQueryParameter("hl.simple.pre", "<Hit>");
        }
        return url.build();
    }

    /** Translate the framework's parsed CQL tree without hiding its specific diagnostics. */
    protected String parseQuery(SRURequest request) throws SRUException {
        if (!request.isQueryType(Constants.FCS_QUERY_TYPE_CQL)) {
            throw new SRUException(SRUConstants.SRU_CANNOT_PROCESS_QUERY_REASON_UNKNOWN,
                    "Only CQL queries are supported by this endpoint.");
        }
        try {
            CQLQueryParser.CQLQuery query = request.getQuery(CQLQueryParser.CQLQuery.class);
            return CQLToSolrConverter.convertCQLtoSolrQuery(query.getParsedQuery());
        } catch (SRUException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new SRUException(SRUConstants.SRU_CANNOT_PROCESS_QUERY_REASON_UNKNOWN,
                    "Unable to translate the CQL query.", e);
        }
    }

    /** An omitted or empty context selects the configured default corpus. */
    protected List<String> parsePids(SRURequest request) {
        String context = request.getExtraRequestData(DzpConstants.X_FCS_CONTEXT_KEY);
        if (context == null || context.isEmpty()) {
            return defaultCorpusId == null ? List.of() : List.of(defaultCorpusId);
        }
        return Arrays.asList(context.split(DzpConstants.X_FCS_CONTEXT_SEPARATOR));
    }

    /** Warn for each unknown PID; an entirely invalid context must not broaden the search. */
    protected List<String> checkPids(List<String> requestedPids, SRUDiagnosticList diagnostics) throws SRUException {
        List<String> knownPids = new ArrayList<>();
        for (String pid : requestedPids) {
            if (resourcePids.contains(pid)) {
                knownPids.add(pid);
            } else {
                diagnostics.addDiagnostic(Constants.FCS_DIAGNOSTIC_PERSISTENT_IDENTIFIER_INVALID, pid,
                        "Resource PID is unknown or not searchable by this endpoint.");
            }
        }
        if (knownPids.isEmpty()) {
            throw new SRUException(SRUConstants.SRU_UNSUPPORTED_PARAMETER_VALUE,
                    "No searchable resource was selected by x-fcs-context.");
        }
        return knownPids;
    }

    /** This backend searches one corpus per request; never silently choose the first of several. */
    protected String checkPid(List<String> pids) throws SRUException {
        if (pids.size() > 1) {
            throw new SRUException(SRUConstants.SRU_UNSUPPORTED_PARAMETER_VALUE,
                    "x-fcs-context must select a single resource.");
        }
        return pids.isEmpty() ? defaultCorpusId : pids.get(0);
    }

    /**
     * Validate optional additional views and warn for each unavailable identifier.
     * This basic endpoint always sends HITS; a selection does not suppress it.
     */
    protected List<String> parseDataViews(SRURequest request, SRUDiagnosticList diagnostics, String pid)
            throws SRUException {
        String selection = request == null ? null : request.getExtraRequestData(DzpConstants.X_FCS_DATAVIEWS_KEY);
        if (selection == null || selection.isEmpty()) {
            return List.of();
        }
        if (endpointDescription == null) {
            throw new SRUException(SRUConstants.SRU_GENERAL_SYSTEM_ERROR, "Endpoint description is unavailable.");
        }
        ResourceInfo resource = pid == null || pid.isBlank() || EndpointDescription.PID_ROOT.equals(pid)
                ? null : endpointDescription.getResource(pid);
        if (resource == null) {
            throw new SRUException(Constants.FCS_DIAGNOSTIC_PERSISTENT_IDENTIFIER_INVALID, pid,
                    "Resource PID is unknown.");
        }
        List<DataView> available = resource.getAvailableDataViews();
        List<String> allowed = new ArrayList<>();
        for (String view : selection.split(DzpConstants.X_FCS_DATAVIEWS_SEPARATOR)) {
            boolean supported = available != null && available.stream()
                    .anyMatch(candidate -> candidate != null && view.equals(candidate.getIdentifier()));
            if (supported) {
                allowed.add(view);
            } else {
                diagnostics.addDiagnostic(Constants.FCS_DIAGNOSTIC_REQUESTED_DATA_VIEW_INVALID, view,
                        "Requested DataView is not available for this resource.");
            }
        }
        return allowed;
    }
}
