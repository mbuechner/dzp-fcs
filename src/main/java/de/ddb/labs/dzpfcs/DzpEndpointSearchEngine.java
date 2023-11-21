/*
 * Copyright 2023 Michael Büchner, Deutsche Digitale Bibliothek
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
package de.ddb.labs.dzpfcs;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.ReadContext;
import com.jayway.jsonpath.TypeRef;
import com.jayway.jsonpath.spi.json.JacksonJsonProvider;
import com.jayway.jsonpath.spi.json.JsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import com.jayway.jsonpath.spi.mapper.MappingProvider;
import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.servlet.ServletContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import de.ddb.labs.dzpfcs.searcher.Results;
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
import eu.clarin.sru.server.fcs.parser.QueryParserException;
import eu.clarin.sru.server.fcs.utils.SimpleEndpointDescriptionParser;
import de.ddb.labs.dzpfcs.query.CQLToSolrConverter;
import de.ddb.labs.dzpfcs.searcher.ResultsEntry;
import eu.clarin.sru.server.SRUServer;
import java.util.EnumSet;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Our implemention of a simple search engine to be used as a CLARIN-FCS
 * endpoint.
 *
 * @see SimpleEndpointSearchEngineBase
 */
public class DzpEndpointSearchEngine extends SimpleEndpointSearchEngineBase {

    private static final Logger LOGGER = LogManager.getLogger(DzpEndpointSearchEngine.class);

    // set in `src/main/webapp/WEB-INF/web.xml` if you want to package a custom endpoint-description.xml file at another location
    private static final String RESOURCE_INVENTORY_URL = "de.ddb.labs.dzpfcs.resourceInventoryURL";

    private static final String DDB_API = "https://api.deutsche-digitale-bibliothek.de/search/index/newspaper-issues/select"
            + "?q={{query}}"
            + "&hl=true"
            + "&hl.fl=plainpagefulltext"
            + "&hl.bs.type=SENTENCE"
            + "&hl.fragsize=512"
            + "&hl.method=fastVector"
            + "&fl=id,paper_title,pagenumber"
            + "&df=plainpagefulltext"
            + "&hl.simple.pre=<hit>"
            + "&hl.simple.post=</hit>"
            + "&rows={{rows}}"
            + "&start={{start}}";

    private Dispatcher dispatcher = null;

    private OkHttpClient client = null;

    /**
     * Endpoint Description with resources, capabilities etc.
     */
    private static EndpointDescription endpointDescription;

    /**
     * List of our endpoint's resources (identified by PID Strings)
     */
    private static List<String> pids;

    /**
     * Our default corpus if SRU requests do no explicitely request a resource
     * by PID with the <code>x-fcs-context</code> parameter. Must not be
     * <code>null</code>!
     */
    private static String defaultCorpusId = null;

    /**
     * Read an environment variable from <code>java:comp/env/paramName</code>
     * and return the value as Object.
     *
     * @param paramName the environment variables name to extract the value from
     * @return the environment variable value as Object
     */
    protected Object readJndi(String paramName) {
        Object jndiValue = null;
        try {
            final InitialContext ic = new InitialContext();
            jndiValue = ic.lookup("java:comp/env/" + paramName);
        } catch (NamingException e) {
            // handle exception
        }
        return jndiValue;
    }

    /**
     * Read an environment variable and return the value as String.
     *
     * @param paramName the environment variables name to extract the value from
     * @return the environment variable value as String
     */
    protected String getEnvParam(String paramName) {
        return (String) readJndi("param/" + paramName);
    }

    /**
     * Load the {@link EndpointDescription} from the JAR resources or from the
     * <code>RESOURCE_INVENTORY_URL</code>.
     *
     * @param context the {@link ServletContext} for the Servlet
     * @param params additional parameters gathered from the Servlet
     * configuration and Servlet context.
     * @return the {@link EndpointDescription} object
     * @throws SRUConfigException an error occurred during loading/reading the
     * <code>endpoint-description.xml</code> file
     */
    protected EndpointDescription loadEndpointDescriptionFromURI(ServletContext context, Map<String, String> params) throws SRUConfigException {
        try {
            URL url;
            String riu = params.get(RESOURCE_INVENTORY_URL);
            if ((riu == null) || riu.isEmpty()) {
                url = context.getResource("/WEB-INF/endpoint-description.xml");
                LOGGER.debug("using bundled 'endpoint-description.xml' file");
            } else {
                url = new File(riu).toURI().toURL();
                LOGGER.debug("using external file '{}'", riu);
            }

            return SimpleEndpointDescriptionParser.parse(url);
        } catch (MalformedURLException mue) {
            throw new SRUConfigException("Malformed URL for initializing resource info inventory", mue);
        }
    }

    /**
     * Parses the list of root resource PIDs from the
     * {@link EndpointDescription}.
     *
     * Note: This only considers root resources and not subresources!
     *
     * @param ed the {@link EndpointDescription} for the Servlet
     * @return a list of String with root resource PIDs
     * @throws eu.clarin.sru.server.SRUException
     */
    protected List<String> getCollectionsFromEndpointDescription(EndpointDescription ed) throws SRUException {
        // NOTE: only root resources!
        return ed.getResourceList(EndpointDescription.PID_ROOT).stream().map(ResourceInfo::getPid)
                .collect(Collectors.toList());
    }

    /**
     * Create {@link EndpointDescription} for this servlet.
     *
     * @param context
     * @param config
     * @param params
     * @return
     * @throws eu.clarin.sru.server.SRUConfigException
     * @see #loadEndpointDescriptionFromURI(ServletContext, Map)
     * @see
     * SimpleEndpointSearchEngineBase#createEndpointDescription(ServletContext,
     * SRUServerConfig, Map)
     */
    @Override
    protected EndpointDescription createEndpointDescription(ServletContext context, SRUServerConfig config,
            Map<String, String> params) throws SRUConfigException {
        return loadEndpointDescriptionFromURI(context, params);
    }

    /**
     * Initialize the search engine. This initialization should be tailed
     * towards your environment and needs.
     *
     * @param context the {@link ServletContext} for the Servlet
     * @param config the {@link SRUServerConfig} object for this search engine
     * @param queryParsersBuilder the {@link SRUQueryParserRegistry.Builder}
     * object to be used for this search engine. Use to register additional
     * query parsers with the {@link SRUServer}.
     * @param params additional parameters gathered from the Servlet
     * configuration and Servlet context.
     * @throws SRUConfigException if an error occurred
     *
     * @see SimpleEndpointSearchEngineBase#doInit(ServletContext,
     * SRUServerConfig, SRUQueryParserRegistry.Builder, Map)
     */
    @Override
    protected void doInit(ServletContext context, SRUServerConfig config, SRUQueryParserRegistry.Builder queryParsersBuilder, Map<String, String> params) throws SRUConfigException {

        LOGGER.info("SRUServlet::doInit {}", config.getPort());

        /* register custom query parsers */
        // queryParsersBuilder.register(new YourQLQueryParser());

        /* load and store endpoint description */
        endpointDescription = createEndpointDescription(context, config, params);

        /* process endpoint description, load available PIDs */
        try {
            pids = getCollectionsFromEndpointDescription(endpointDescription);
        } catch (SRUException e) {
            throw new SRUConfigException("Error extracting resource pids", e);
        }
        LOGGER.info("Got root resource PIDs: {}", pids);

        /* set default corpus ID */
        // or params.get("DEFAULT_RESOURCE_PID")
        // defaultCorpusId = getEnvParam("DEFAULT_RESOURCE_PID");  // FIXME
        defaultCorpusId = params.get("DEFAULT_RESOURCE_PID");
        LOGGER.info("Got defaultCorpusId resource PID: {}", defaultCorpusId);
        if (defaultCorpusId == null || !pids.contains(defaultCorpusId)) {
            throw new SRUConfigException("Parameter 'DEFAULT_RESOURCE_PID' contains unknown resource pid!");
        }

        // configure JsonPath to use Jackson
        Configuration.setDefaults(new Configuration.Defaults() {

            private final JsonProvider jsonProvider = new JacksonJsonProvider();
            private final MappingProvider mappingProvider = new JacksonMappingProvider();

            @Override
            public JsonProvider jsonProvider() {
                return jsonProvider;
            }

            @Override
            public MappingProvider mappingProvider() {
                return mappingProvider;
            }

            @Override
            public Set<Option> options() {
                return EnumSet.noneOf(Option.class);
            }
        });

        this.dispatcher = new Dispatcher(Executors.newFixedThreadPool(128));
        dispatcher.setMaxRequests(16);
        dispatcher.setMaxRequestsPerHost(16);

        this.client = new OkHttpClient().newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .dispatcher(dispatcher)
                .connectTimeout(180, TimeUnit.SECONDS)
                .build();
    }

    /**
     * Handle a <em>searchRetrieve</em> operation.
     *
     * @param config
     * @param request
     * @param diagnostics
     * @return
     * @throws eu.clarin.sru.server.SRUException
     * @see SR
     */
    @Override
    public SRUSearchResultSet search(SRUServerConfig config, SRURequest request, SRUDiagnosticList diagnostics) throws SRUException {
        /* parse and translate query */
        final String myQuery = parseQuery(request);

        /* validate params */
        List<String> pids = parsePids(request);
        pids = checkPids(pids, diagnostics);
        LOGGER.debug("Search restricted to PIDs: {}", pids);
        /* we restrict our search to the first PID (since most clients only request a single one?) */
        final String pid = checkPid(pids);
        LOGGER.debug("Search restricted to first PID: {}", pid);

        final List<String> dataviews = parseDataViews(request, diagnostics, pid);
        LOGGER.debug("Search requested dataviews: {}", dataviews);

        final int startRecord = ((request.getStartRecord() < 1) ? 1 : request.getStartRecord()) - 1;
        final int maximumRecords = request.getMaximumRecords();

        // check for correct startRecord
        final String apiQuery01 = DDB_API
                .replace("{{query}}", myQuery)
                .replace("{{rows}}", Integer.toString(0))
                .replace("{{start}}", Integer.toString(0));

        String json = "";
        final Request apiRequest01 = new Request.Builder()
                .url(apiQuery01)
                .build();

        try (final Response response01 = client.newCall(apiRequest01).execute()) {
            json = response01.body().string();
            if (!response01.isSuccessful()) {
                throw new Exception("Response code of DDB-API is " + response01.code() + ". Request URL: " + response01.request().url().toString());
            }
        } catch (Exception e) {
            throw new SRUException(SRUConstants.SRU_GENERAL_SYSTEM_ERROR, e.getMessage());
        }

        final ReadContext ctx01 = JsonPath.parse(json);
        final Integer numFound = ctx01.read("$.response.numFound", Integer.class);

        if (startRecord > numFound) {
            throw new SRUException(SRUConstants.SRU_FIRST_RECORD_POSITION_OUT_OF_RANGE);
        }

        // query results
        final String apiQuery02 = DDB_API
                .replace("{{query}}", myQuery)
                .replace("{{rows}}", Integer.toString(maximumRecords))
                .replace("{{start}}", Integer.toString(startRecord));

        json = "";
        final Request apiRequest02 = new Request.Builder()
                .url(apiQuery02)
                .build();

        try (final Response response02 = client.newCall(apiRequest02).execute()) {
            json = response02.body().string();
            if (!response02.isSuccessful()) {
                throw new Exception("Response code of DDB-API is " + response02.code() + ". Request URL: " + response02.request().url().toString());
            }
        } catch (Exception e) {
            throw new SRUException(SRUConstants.SRU_GENERAL_SYSTEM_ERROR, e.getMessage());
        }

        final ReadContext ctx02 = JsonPath.parse(json);

        final List<ResultsEntry> docList = ctx02.read("$.response.docs[*]", new TypeRef<List<ResultsEntry>>() {
        });

        for (ResultsEntry doc : docList) {
            if (doc.getId() == null || doc.getId().isBlank()) {
                continue;
            }
            final String jsonQuery = "$.highlighting['" + doc.getId() + "'].plainpagefulltext[*]";
            final List<String> list = ctx02.read(jsonQuery, new TypeRef<List<String>>() {
            });
            doc.setPlainpagefulltext(list);
        }

        /* start search (query = myQuery, offset = startRecord, limit = maximumRecords) */
        final Results results = new Results(pid, myQuery, docList, numFound, startRecord);

        if (results == null) {
            throw new SRUException(SRUConstants.SRU_GENERAL_SYSTEM_ERROR, "Error in Searcher");
        }

        /* wrap results into custom SRUSearchResultSet */
        return new DzpSRUSearchResultSet(config, request, diagnostics, dataviews, results);
    }

    /**
     * Extract and parse the query from the {@link SRURequest}.
     *
     * @param request the {@link SRURequest} with request parameters
     * @return the raw query as String
     * @throws SRUException if an error occurred trying to extract or to parse
     * the query
     *
     * @see #search(SRUServerConfig, SRURequest, SRUDiagnosticList)
     */
    protected String parseQuery(SRURequest request) throws SRUException {
        final String myQuery;
        if (request.isQueryType(Constants.FCS_QUERY_TYPE_CQL)) {
            /*
             * Got a CQL query (either SRU 1.1 or higher).
             * Translate to a proper MYQUERY query ...
             */
            final CQLQueryParser.CQLQuery q = request.getQuery(CQLQueryParser.CQLQuery.class);
            LOGGER.info("FCS-CQL query: {}", q.getRawQuery());

            try {
                myQuery = CQLToSolrConverter.convertCQLtoSolrQuery(q.getParsedQuery());
                LOGGER.debug("Converted Solr: {}", myQuery);
            } catch (QueryParserException e) {
                throw new SRUException(SRUConstants.SRU_CANNOT_PROCESS_QUERY_REASON_UNKNOWN, "Converting query with queryType 'cql' to MYQUERY failed.", e);
            }
        } else {
            /*
             * Got something else we don't support. Send error ...
             */
            throw new SRUException(SRUConstants.SRU_CANNOT_PROCESS_QUERY_REASON_UNKNOWN, "Queries with queryType '" + request.getQueryType() + "' are not supported by this FCS Endpoint.");
        }
        return myQuery;
    }

    /**
     * Extract and parse the requested resource PIDs from the
     * {@link SRURequest}.
     *
     * Returns the list of resource PIDs if <code>x-fcs-context</code> parameter
     * was used and it was non-empty. If no FCS context was set, then return
     * with the <code>defaultCorpusId</code>.
     *
     * @param request the {@link SRURequest} with request parameters
     * @return a list of String resource PIDs
     * @throws eu.clarin.sru.server.SRUException
     *
     * @see #search(SRUServerConfig, SRURequest, SRUDiagnosticList)
     */
    protected List<String> parsePids(SRURequest request) throws SRUException {
        boolean hasFcsContextCorpus = false;
        String fcsContextCorpus = "";

        for (String erd : request.getExtraRequestDataNames()) {
            if (DzpConstants.X_FCS_CONTEXT_KEY.equals(erd)) {
                hasFcsContextCorpus = true;
                fcsContextCorpus = request.getExtraRequestData(DzpConstants.X_FCS_CONTEXT_KEY);
                break;
            }
        }
        if (!hasFcsContextCorpus || "".equals(fcsContextCorpus)) {
            LOGGER.debug("Received 'searchRetrieve' request without x-fcs-context - Using default '{}'",
                    defaultCorpusId);
            fcsContextCorpus = defaultCorpusId;
        }
        if (fcsContextCorpus == null) {
            return new ArrayList<>();
        }

        List<String> selectedPids = new ArrayList<>(Arrays.asList(fcsContextCorpus.split(
                DzpConstants.X_FCS_CONTEXT_SEPARATOR)));

        return selectedPids;
    }

    /**
     * Validate the requested resource PIDs from the {@link SRURequest} against
     * the list of resource PIDs declared in the servlet's
     * {@link EndpointDescription}.
     *
     * Returns the list of valid resource PIDs. Generates SRU diagnostics for
     * each invalid/unknown resource PID. If the list of valid PIDs is empty
     * then raise an {@link SRUException}.
     *
     * @param pids the list of resource PIDs
     * @param diagnostics the {@link SRUDiagnosticList} object for storing
     * non-fatal diagnostics
     * @return a list of String resource PIDs
     * @throws SRUException if no valid resource PIDs left
     *
     * @see #search(SRUServerConfig, SRURequest, SRUDiagnosticList)
     * @see #getCollectionsFromEndpointDescription(EndpointDescription)
     * @see #parsePids(SRURequest)
     */
    protected List<String> checkPids(List<String> pids, SRUDiagnosticList diagnostics) throws SRUException {
        // set valid and existing resource PIDs
        List<String> knownPids = new ArrayList<>();
        for (String pid : pids) {
            if (!DzpEndpointSearchEngine.pids.contains(pid)) {
                // allow only valid resources that can be queried by CQL
                diagnostics.addDiagnostic(Constants.FCS_DIAGNOSTIC_PERSISTENT_IDENTIFIER_INVALID, pid, "Resource PID for search is not valid or can not be queried by FCS/CQL!");
            } else {
                knownPids.add(pid);
            }
        }
        if (knownPids.isEmpty()) {
            // if search was restricted to resources but all were invalid, then do we fail?
            // or do we adjust to our default corpus?
            throw new SRUException(SRUConstants.SRU_UNSUPPORTED_PARAMETER_VALUE, "All values passed to '" + DzpConstants.X_FCS_CONTEXT_KEY + "' were not valid PIDs or can not be queried by FCS/CQL.");
        }

        return knownPids;
    }

    /**
     * Validate the requested resource PIDs from the {@link SRURequest} to be
     * only a single PID as this endpoint can only handle searching through one
     * resource at a time.
     *
     * NOTE: The CLARIN SRU/FCS Aggregator also only seems to request results
     * for each resource separately, we only allow requests with one resource!
     *
     * Returns the resource PID. Raises an {@link SRUException} if more than one
     * resource PID in <code>pids</code>.
     *
     * @param pids the list of resource PIDs
     * @return the resource PID as String
     * @throws SRUException if no valid resource PIDs left
     *
     * @see #search(SRUServerConfig, SRURequest, SRUDiagnosticList)
     * @see #checkPids(List, SRUDiagnosticList)
     */
    protected String checkPid(List<String> pids) throws SRUException {
        // NOTE: we only search for first PID
        // (FCS Aggregator only provides one resource PID per search request, so
        // multiple PIDs should usually not happen)
        final String pid;
        if (pids.size() > 1) {
            throw new SRUException(SRUConstants.SRU_UNSUPPORTED_PARAMETER_VALUE, "Parameter '" + DzpConstants.X_FCS_CONTEXT_KEY + "' received multiple PIDs. Endpoint only supports a single PIDs for querying by CQL/FCS-QL/LexCQL.");
        } else if (pids.isEmpty()) {
            pid = defaultCorpusId;
            LOGGER.debug("Falling back to default resource: {}", pid);
            pids.add(pid);
        } else {
            pid = pids.get(0);
        }
        return pid;
    }

    /**
     * Extract and parse the requested result Data Views from the
     * {@link SRURequest}.
     *
     * Returns the list of Data View identifiers if <code>x-fcs-dataviews</code>
     * parameter was used and is non-empty.
     *
     * Validates the requested Data Views against the ones declared in the
     * servlet's {@link EndpointDescription} for the resource identified by the
     * value in <code>pid</code>. For each non-valid Data View generate a SRU
     * diagnostic.
     *
     * @param request the {@link SRURequest} with request parameters
     * @param diagnostics the {@link SRUDiagnosticList} object for storing
     * non-fatal diagnostics
     * @param pid resource PID String, to validate requested Data Views
     * @return a list of String Data View identifiers, may be empty
     * @throws eu.clarin.sru.server.SRUException
     *
     * @see #search(SRUServerConfig, SRURequest, SRUDiagnosticList)
     */
    protected List<String> parseDataViews(SRURequest request, SRUDiagnosticList diagnostics, String pid) throws SRUException {
        List<String> extraDataviews = new ArrayList<>();
        if (request != null) {
            for (String erd : request.getExtraRequestDataNames()) {
                if (DzpConstants.X_FCS_DATAVIEWS_KEY.equals(erd)) {
                    final String dvs = request.getExtraRequestData(DzpConstants.X_FCS_DATAVIEWS_KEY);
                    extraDataviews = new ArrayList<>(
                            Arrays.asList(dvs.split(DzpConstants.X_FCS_DATAVIEWS_SEPARATOR)));
                    break;
                }
            }
        }
        if (extraDataviews.isEmpty()) {
            return new ArrayList<>();
        }

        final Set<String> resourceDataViews = endpointDescription.getResourceList(pid).get(0).getAvailableDataViews().stream().map(DataView::getIdentifier).collect(Collectors.toSet());

        final List<String> allowedDataViews = new ArrayList<>();
        for (String dv : extraDataviews) {
            if (!resourceDataViews.contains(dv)) {
                // allow only valid dataviews for this resource that can be requested
                diagnostics.addDiagnostic(Constants.FCS_DIAGNOSTIC_PERSISTENT_IDENTIFIER_INVALID, pid, "DataViews with identifier '" + dv + "' for resource PID='" + pid + "' is not valid!");
            } else {
                allowedDataViews.add(dv);
            }
        }
        return allowedDataViews;
    }
}
