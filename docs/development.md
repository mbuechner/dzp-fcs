# Development

[Overview and quickstart](../README.md) · [Configuration](configuration.md) · [Logging](logging.md)

## Packages

Production code lives under `de.ddb.labs.dzpfcs`; tests mirror the package structure.

| Package | Responsibility |
|---|---|
| `endpoint` | CLARIN lifecycle, SRU search/result serialization, FCS constants and snippet writing |
| `backend` | HTTP settings and execution, DDB API identification, Solr JSON parsing and internal backend telemetry |
| `model` | Result-page and result-entry data |
| `query` | CQL-to-Solr translation and supported-query validation |
| `logging` | Request filter, request-scoped measurements and structured event payloads |

The request flows from the logging filter through CLARIN into the endpoint, query translation and backend request, then back through result serialization. Models and request telemetry connect these stages. Package-private helpers stay with their owner; public types and methods provide the required package boundaries.

Servlet class names are configured in [`web.xml`](../src/main/webapp/WEB-INF/web.xml). Review logger selectors and the [SpotBugs exclusion](../config/spotbugs-exclude.xml) when moving implementation classes.

## Build and checks

Building requires JDK 25 LTS and Apache Maven. CI and the Docker build use Eclipse Temurin 25.

Run commands from the repository root. Maven builds the Web Application Archive (WAR):

```bash
mvn -B package
```

`mvn -B package` runs the unit and regression tests and builds the WAR. Tests cover real CQL parser output, backend retry/deadline behavior, concurrent JSON parsing, per-endpoint lifecycle state, request telemetry, snippet compatibility and offline FCS schema validation. The two vendored FCS schemas include their source commit and checksums in [`src/test/resources/fcs20/PROVENANCE.txt`](../src/test/resources/fcs20/PROVENANCE.txt).

Run `mvn -B spotbugs:check` after compilation for static analysis. [`config/spotbugs-exclude.xml`](../config/spotbugs-exclude.xml) documents one narrow exclusion: a servlet must receive its writable response stream, so returning the cached observation wrapper is intentional rather than an accidental exposure of mutable application state.

The configured OWASP dependency check is bound to Maven's `verify` phase. It is not executed by the `package` command used in CI and the container build. Run `mvn -B verify` separately when that check is needed; it may require vulnerability-data downloads or a prepared local cache.

The container uses Tomcat 9 with Servlet 4.0 (`javax.servlet`). Tomcat 10 or 11 requires migrating the application and its servlet dependencies to `jakarta.servlet`; see the [Tomcat migration guide](https://tomcat.apache.org/migration-10.html). Review the owned [server.xml](../config/tomcat/server.xml) when upgrading the base image. The existing [Tomcat support announcement](https://tomcat.apache.org/tomcat-9.0.x-eos) describes the 9.0/9.1 support transition.

Run `sh config/tests/docker-entrypoint-test.sh target/entrypoint-tests` for the container entrypoint scenarios. It checks path normalization, WAR naming, public-path overrides, invalid configurations and restarts, retaining its artifacts for inspection.

The Docker build starts from the supplied source tree. [`.dockerignore`](../.dockerignore) excludes local `target/` output, Git metadata and IDE state, so old classes and local test runtimes do not enter the Maven build stage.

## Implementation

This Java servlet was implemented using the [FCS Endpoint Archetype](https://github.com/clarin-eric/fcs-endpoint-archetype). Further information can be found there.

Each successful search normally uses one paginated Solr request and reads the total hit count, records and highlights from that response. The existing SRU pagination and range checks are preserved. The shared OkHttp client reuses eligible HTTP connections across searches; cookie sessions are not required for connection reuse. Transient backend failures can trigger bounded retries within a shared time budget (see [configuration](configuration.md#backend-timeouts-and-retries)).

Result pages take one read-only snapshot of their document list at construction. Snippet setters likewise retain raw backend strings in a read-only snapshot. The getters reuse those snapshots, so counting/indexing records during SRU iteration no longer copies the entire page for every record. This removes quadratic list-copying overhead; individual `ResultsEntry` objects remain mutable and confined to their request.

Each endpoint instance owns its corpus configuration and HTTP client. The parsed inventory is reused for Explain and searches, and the client is released when the servlet stops. Initialization does not mutate JVM-wide JSON settings. `SolrResponseParser` uses one shared immutable Jackson reader and maps only the required fields directly from the response tree; it does not construct another map/DTO graph with JsonPath. Results and parsing trees are local to one search, so parallel calls do not share mutable page state.

Lombok generates the remaining DTO accessors at compile time and is not packaged as a runtime library. It does not provide synchronization or make mutable records safe for concurrent mutation.

`SnippetWriter` writes the first snippet directly with `XMLStreamWriter`. Only the first literal `<Hit>`/`</Hit>` pair becomes an FCS Hit element; subsequent markers disappear while their text remains. Other tags and entity-looking strings remain literal text. The writer preserves the former character filtering, marker-removal order and XML 1.0 newline normalization, and rejects unpaired/out-of-order markers and forbidden control characters. The endpoint supports CQL Basic Search and always emits its HITS DataView for a normal record. The production path no longer escapes into an XML string, applies regular expressions, converts to UTF-8 and reparses it with SAX. The former pipeline remains only in differential tests; Commons Text is a test dependency.

## SRU and FCS responses

Explain describes the configured public base URL with a separate hostname and database path (see [configuration](configuration.md#endpoint-path-and-public-url)). Its searchable index is `cql.serverChoice`. Term-only queries, phrases and unmodified `AND`/`OR` retain their existing search behavior. Unsupported CQL relations and modifiers return SRU diagnostics instead of silently becoming phrase searches.

FCS records must contain a HITS representation. If the first backend snippet is missing, malformed, or has no genuine non-empty first hit after normalization, that record is returned as surrogate diagnostic 67 (record not available in this schema). The total hit count and record positions remain unchanged. Later snippets or hits are not substituted, and the application does not invent highlighting. Unavailable records therefore remain visible in the requested page without producing an invalid FCS payload.

The optional request echo is disabled with `eu.clarin.sru.server.echoRequests=false` because `sru-server` 1.14.0 emits legacy XCQL inside SRU-2.0 echoes. Search terms remain available in the application's structured request logs. Native SRU-2.0 requests omit the `version` parameter; SRU-1.2 requests explicitly use `version=1.2`.

The Servlet-4.0 descriptor sets UTF-8 as the default request encoding, including form POSTs without a charset. A client-supplied charset still takes precedence. Invalid backend JSON or required Solr fields result in an SRU system diagnostic; parsing failures do not trigger a second backend search.

Backend transport and HTTP failures use a fixed client-facing diagnostic rather than exposing exception messages or internal request URLs. The original exception remains its server-side cause; request IDs, backend status and timeout/timing events support diagnosis without putting these details in the public SRU response.

Two serializer limitations remain in the upstream SRU library: string record escaping can produce malformed outer XML, and SRU-2.0 responses use generic `application/xml` rather than the required `application/sru+xml`. Use the default embedded XML representation until string escaping is fixed upstream.

## Solr request costs

The request returns only `id,pagenumber`; these fields supply the resource identifier and newspaper-page link. `paper_title` is not used in the SRU output. Count-only searches (`maximumRecords=0`) send `rows=0&start=0&hl=false` and omit highlight options. A positive backend offset can still make Solr collect a deep top-N result set even when no documents are returned; the application keeps the original SRU offset locally for its unchanged range check and result metadata. Only count-only requests use `sort=_docid_ asc`, Solr's built-in document-ID order, to avoid relevance scoring without needing an indexed sort field or DocValues. No documents are returned or merged in this case. Normal page requests retain their requested offset and ranking. Searches returning records retain the existing Unified Highlighter, sentence boundaries and `<Hit>` markers. The unused `hl.bs.separator` parameter is omitted because it only applies to `hl.bs.type=SEPARATOR`, not `SENTENCE`.

The count optimization is restricted to `rows=0,start=0`; document-ID ordering is not used for regular pages or distributed document merging. Solr may still collect a small superset because of its configured query-result cache window (20 in the supplied configuration), but it need not score those matches. See the [Solr 8.5 collector/window logic](https://github.com/apache/lucene-solr/blob/releases/lucene-solr/8.5.0/solr/core/src/java/org/apache/solr/search/SolrIndexSearcher.java#L1320) and Solr's own [count-oriented scoring avoidance](https://github.com/apache/lucene-solr/blob/releases/lucene-solr/8.11.0/solr/core/src/java/org/apache/solr/spelling/SpellCheckCollator.java#L121).

All searches request `wt=json&indent=false&echoParams=none&omitHeader=false`. This avoids pretty-printing whitespace and the redundant request-parameter echo while retaining header fields such as `QTime` and `partialResults`. Whitespace inside snippet strings remains untouched. These request-only changes work with the existing index and preserve the query, relevance order, exact hit count and SRU snippets. They do not establish a measured production speedup. See [Solr response parameters](https://solr.apache.org/guide/8_11/common-query-parameters.html#echoparams-parameter) and the [Solr 8.5 writer implementation](https://github.com/apache/lucene-solr/blob/releases/lucene-solr/8.5.0/solr/core/src/java/org/apache/solr/response/TextResponseWriter.java#L63).

For example, a 25-record page uses the following parameters (shown before URL encoding; the application encodes them with `HttpUrl`):

```text
q="Berlin"
df=plainpagefulltext
start=0
rows=25
fl=id,pagenumber
wt=json
indent=false
echoParams=none
omitHeader=false
hl=true
hl.method=unified
hl.fl=plainpagefulltext
hl.bs.type=SENTENCE
hl.simple.pre=<Hit>
hl.simple.post=</Hit>
```

The supplied [newspaper-issues schema](https://dev.fiz-karlsruhe.de/stash/projects/DDB/repos/ddb-backend/browse/Cortex/conf/solr/newspaper-issues/conf/schema.xml) defines `plainpagefulltext` as stored/indexed `text`, without postings offsets or term vectors. Consequently, Unified Highlighting must analyze the stored page text to locate matches. Its analyzer includes word splitting, lowercasing and synonym processing; repeating that work across many pages is a plausible bottleneck. The separately configured offsets and light term vectors on `pagefulltext`/`text_ocr` do not accelerate `plainpagefulltext` highlighting. Switching fields would also switch analyzers and the OCR data representation, so it is not an equivalent request optimization.

A larger backend improvement to evaluate is enabling postings offsets specifically on `plainpagefulltext`, leaving its analyzer unchanged:

```xml
<field name="plainpagefulltext" type="text" indexed="true" stored="true"
       multiValued="false" storeOffsetsWithPositions="true" />
```

This requires a complete backend reindex and adds index storage. It is a recommendation for the backend, not a schema change applied by this application. Compare representative phrase/synonym queries, hit counts, ordering and snippets before rollout. Unified Highlighting selects available offsets automatically; forcing `hl.offsetSource=POSTINGS` against the current index would be incorrect. Light term vectors can additionally help multi-term/wildcard highlighting when that use case is needed. See the [Solr highlighting guide](https://solr.apache.org/guide/8_11/highlighting.html#schema-options-and-performance-considerations).

The supplied [Solr configuration](https://dev.fiz-karlsruhe.de/stash/projects/DDB/repos/ddb-backend/browse/Cortex/conf/solr/newspaper-issues/conf/solrconfig.xml) enables lazy stored-field loading and gives `/select` no additional defaults. Listing facet, stats and similar components does not enable their expensive features by itself. Adding disabled-feature flags is therefore not an established speedup. The setting `luceneMatchVersion=8.5.0` is a compatibility setting and does not establish the deployed Solr version.

Another backend setting worth profiling is `queryResultMaxDocsCached=0`. In Solr 8.x, this prevents non-empty document-list supersets from being inserted into `queryResultCache`; it does not disable cache lookups, empty-result caching or the separate filter/document caches. A positive threshold may help repeated queries and page requests, depending on the workload and heap budget. This cache stores result document IDs, not generated highlight snippets. See the [Solr 8.5 cache insertion condition](https://github.com/apache/lucene-solr/blob/releases/lucene-solr/8.5.0/solr/core/src/java/org/apache/solr/search/SolrIndexSearcher.java#L1461) and [query cache settings](https://solr.apache.org/guide/8_11/query-settings-in-solrconfig.html#queryresultmaxdocscached).

Avoid changing the default field, phrase handling, relevance sorting, analyzed-text limit or exact counting merely to reduce latency: those changes can alter results or snippets. In particular, `timeAllowed` can produce partial results; it is not a transparent substitute for the application's timeout policy. Preserve the response header for timing and partial-result diagnostics. Backend profiling should compare the same queries and pages with highlighting on/off and then compare the existing and reindexed field under comparable cache/load conditions.
