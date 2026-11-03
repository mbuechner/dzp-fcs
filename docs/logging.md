# Logging and usage statistics

[Overview and quickstart](../README.md) · [Configuration](configuration.md) · [Development](development.md)

Application logs are UTF-8 JSON Lines on stdout: one complete JSON object per event, without ANSI colors or literal multiline stack traces. The envelope contains `timestamp` in UTC, `level`, `logger`, `thread` and, inside an HTTP request, `requestId`. Structured application fields are in the `message` object. Ordinary library messages remain JSON strings in `message`; Tomcat's own JUL/container logs retain their separate configuration.

Every HTTP request receives a server-generated UUID in the `X-Request-ID` response header. The same ID correlates filter, search, backend and framework logs. A client-supplied ID is recorded separately as `message.upstreamRequestId`. Request scopes and their Log4j context are restored in `finally`, so parallel requests and reused servlet threads do not share IDs or counters.

| Event (`message.event`) | Meaning |
|---|---|
| `request.completed` | Exactly one summary per synchronous HTTP request, including parsing, backend work and SRU serialization. Use this event for request/usage counts. |
| `search.completed` | One completed search-engine invocation, including translation, validation, backend work and JSON mapping. |
| `backend.attempt.completed` | One started application-level HTTP call, including the entire body read and response close. |
| `backend.retry.scheduled` | A retry has been scheduled. Interruption during its delay can prevent the next call from starting. |
| `backend.completed` | Exactly one summary per backend operation, also when it times out waiting for a concurrency permit. |
| `solr.partial_results` | The optional Solr response header reports partial/omitted results. Logging observes this without changing the existing response behavior. |

`request.completed` records the decoded `query`, `operation`, `queryType`, requested `startRecord`/`maximumRecords`, `clientUserAgent`, `clientAddress`, HTTP method and path. The application intentionally logs complete search text for usage analysis; JSON escaping keeps quotes, line breaks and backslashes inside their original string values. Strings have a bounded layout buffer: exceptionally long queries above 65,536 UTF-16 code units additionally carry `queryChunked=true`, `queryLength` and `queryChunks`. Concatenate the chunks to reconstruct the complete query; `query` is then a shortened preview. Normal queries are unchanged. These summaries do not record cookies, authorization headers, arbitrary headers or request bodies.

The client address is `HttpServletRequest.getRemoteAddr()`, as supplied by the container. `forwardedFor` is the separate, **unverified** `X-Forwarded-For` header. Behind OpenShift the socket address can belong to a router. Establish trusted client-IP handling in the container/proxy configuration, for example with a correctly configured [Tomcat Remote IP Valve](https://tomcat.apache.org/tomcat-9.0-doc/config/valve.html#Remote_IP_Valve); the application never promotes arbitrary forwarded headers to trusted client identity. User-Agent and upstream IDs are also client-provided descriptions, not authenticated identities.

All duration fields are JSON numbers in milliseconds, with microsecond precision where available:

| Summary field | Measurement |
|---|---|
| `requestDurationMs` | Wall time through the synchronous filter chain, including response generation. It does not prove that a remote client received every byte. |
| `searchDurationMs` | Search-engine time, excluding subsequent framework validation/serialization. Absent if the framework did not invoke the engine. |
| `backendDurationMs` | Total backend-operation time, including queueing, active calls, retries and measured backoff. |
| `backendActiveMs` | Sum of HTTP-call durations through body read/close, including network waits and native OkHttp recovery. |
| `backendQueueMs` | Total time waiting for a concurrency permit, including unsuccessful waits. |
| `backendBackoffMs` | Actual time spent in retry delays, including interrupted delays. |
| `solrQTimeMs` | Optional server-reported `QTime` from the final successful Solr response. It is not the duration of the complete retry sequence. |

`backendCalls`, `backendAttempts`, `backendRetries`, `backendTimeouts` and `backendStatus` provide counts/status alongside the timings. Retries count additional calls that actually started; a scheduled retry alone does not increase this counter. Attempts and timeout counts describe observable application-level calls/responses. OkHttp may perform internal recovery exchanges within one attempt; those contribute to latency but are not individually counted here. No backend operation means zero backend counters/durations rather than a missing request event.

Backend timeout categories are `queue` (concurrency wait exhausted the shared budget), `budget` (the total operation deadline), `socket` (a socket timeout without an unjustified connect/read distinction), and `upstream` (visible HTTP 408/504). Interrupted threads and canceled calls have distinct outcomes. A recovered upstream timeout still increments `backendTimeouts` even if the eventual `backendOutcome` is `success`. Attempt events additionally provide `attempt`, `maxAttempts`, `attemptBudgetMs`, `durationMs`, `status`, `phase` and `errorClass`; `backendCallId` joins attempts and their operation summary.

HTTP status is the status observed at filter exit; the container may subsequently generate an error page for an uncaught exception. `chainOutcome` therefore distinguishes `returned` from `threw`. `engineOutcome` is `not_invoked`, `returned`, `diagnostic` or `exception`. **HTTP 200 and an engine return do not establish final SRU success:** the framework can emit an SRU diagnostic before invoking the engine or after it returns (for example diagnostic 61). Engine-thrown diagnostics include `sruDiagnostic`; this is not a complete capture of every framework-generated diagnostic. The application does not buffer or reparse response XML to infer one. `responseWriteErrorClass` records observed output-stream acquisition, write and flush failures even when the framework catches them. `responseBytes` counts fully successful writes since the last reset, not confirmed delivery; a partially completed failing write can make the count a lower bound.

For example, extract one row per search request with `jq` (non-JSON container lines and ordinary library messages are skipped):

```bash
jq -Rc 'fromjson? | select(.message.event? == "request.completed" and .message.operation == "searchRetrieve") | {requestId, timestamp, query: (if .message.queryChunked then (.message.queryChunks | join("")) else .message.query end), client: .message.clientUserAgent, requestMs: .message.requestDurationMs, backendMs: .message.backendDurationMs, queueMs: .message.backendQueueMs, backoffMs: .message.backendBackoffMs, attempts: .message.backendAttempts, timeouts: .message.backendTimeouts, solrMs: .message.solrQTimeMs}' application.log
```

Usage statistics count `request.completed`, not attempt or retry events. Aggregate requests per time interval/client, and calculate p50/p95/p99 from `requestDurationMs`; restrict backend-latency percentiles to summaries with `backendCalls > 0`. Sum `backendTimeouts` and `backendRetries` to measure failures and retry load, or count summaries with `backendTimeouts > 0` to measure affected requests. Query text and request IDs remain searchable log fields rather than high-cardinality metric labels. Backend/application time differences also include logging and other overhead and should not be interpreted as CPU time.
