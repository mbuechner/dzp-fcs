# Configuration

[Overview and quickstart](../README.md) · [Development](development.md) · [Logging](logging.md)

Java backend settings are loaded through dotenv-java. Process environment variables take precedence over the bundled [`.env`](../src/main/resources/.env). Container deployment, public URL and HTTP compression are configured by the container process environment, separately from that Java resource. Restart the container after configuration changes.

## Endpoint path and public URL

The container serves the endpoint at `/` by default. Set `DZP_FCS_CONTEXT_PATH` when starting the container to deploy it at another path, including nested paths such as `/app/dzp-fcs`. The same image works in both environments; no rebuild is needed.

| Container environment variable | Meaning | Default |
|---|---|---|
| `DZP_FCS_CONTEXT_PATH` | Actual application path in Tomcat | `/` |
| `DZP_FCS_PUBLIC_PATH` | Public path advertised by SRU Explain | Value of `DZP_FCS_CONTEXT_PATH` |
| `DZP_FCS_PUBLIC_HOST` | Public hostname, without scheme or path | `localhost` |
| `DZP_FCS_PUBLIC_TRANSPORT` | Public URL scheme (`http` or `https`) | `http` |
| `DZP_FCS_PUBLIC_PORT` | Public port, including the external HTTPS port behind a proxy | `8080` |

For production, with the proxy forwarding the complete request path to Tomcat:

```bash
docker run --rm -p 8080:8080 \
  -e DZP_FCS_CONTEXT_PATH=/app/dzp-fcs \
  -e DZP_FCS_PUBLIC_HOST=labs.deutsche-digitale-bibliothek.de \
  -e DZP_FCS_PUBLIC_TRANSPORT=https \
  -e DZP_FCS_PUBLIC_PORT=443 \
  dzp-fcs
```

Tomcat then accepts requests at `http://localhost:8080/app/dzp-fcs` and `http://localhost:8080/app/dzp-fcs/`; Explain advertises `https://labs.deutsche-digitale-bibliothek.de/app/dzp-fcs`.

If the proxy removes `/app/dzp-fcs` before forwarding, keep `DZP_FCS_CONTEXT_PATH=/` and set `DZP_FCS_PUBLIC_PATH=/app/dzp-fcs` along with the public hostname, scheme and port above. This lets Explain advertise the external URL while Tomcat receives requests at `/`.

These are **container process environment variables**, supplied via `docker run -e`, Docker `--env-file`, or the OpenShift Deployment's `env` section. `src/main/resources/.env` configures the Java backend client; it does not configure container deployment or Tomcat's XML parsing. Restart with a fresh container when changing the context path. Paths must start with `/`; trailing slashes are removed, except for `/` itself. Segments may contain ASCII letters, digits, `.`, `_`, `~` and `-`; empty segments and `.`/`..` segments are rejected. The exact context path `/ROOT` is reserved by Tomcat's WAR naming convention and is also rejected.

The entrypoint deploys the WAR using [Tomcat's context naming convention](https://tomcat.apache.org/tomcat-9.0-doc/config/context.html#Naming): `ROOT.war` for `/`, or `app#dzp-fcs.war` for `/app/dzp-fcs`. For a WAR deployed outside this image, choose the corresponding WAR filename yourself, enable `org.apache.tomcat.util.digester.EnvironmentPropertySource` in Tomcat's `catalina.properties`, and set all four `DZP_FCS_PUBLIC_*` variables explicitly. Their XML defaults are `http://localhost:8080/`; the entrypoint's path fallback only applies inside the container.

Run `sh config/tests/docker-entrypoint-test.sh target/entrypoint-tests` to check deployment naming, path normalization, public-path overrides, restart behavior and invalid configurations without starting Tomcat. The test retains its temporary artifacts under the supplied directory.

## HTTP response compression

The container uses Tomcat's native gzip support. [config/tomcat/server.xml](../config/tomcat/server.xml) configures the HTTP connector directly; the Dockerfile enables Tomcat's `EnvironmentPropertySource`. No servlet compression filter or response buffering is needed. The other active server settings follow Tomcat 9.0.123; review this owned configuration when upgrading the base image.

| Container environment variable | Meaning | Default |
|---|---|---|
| `DZP_FCS_HTTP_COMPRESSION` | `on` enables negotiated gzip; `off` disables it | `on` |
| `DZP_FCS_HTTP_COMPRESSION_MIN_BYTES` | Minimum size when the response length is known | `2048` |
| `DZP_FCS_HTTP_COMPRESSIBLE_MIME_TYPES` | Comma-separated list replacing Tomcat's compression MIME list | Tomcat's standard types plus `application/sru+xml` |

These are **container process environment variables**, for example in the OpenShift Deployment's `env` section. The WAR's `.env` file does not configure Tomcat. Changes take effect after restarting the container. The default MIME list includes `application/xml`, `text/xml` and the future SRU-2.0 type `application/sru+xml`, while retaining Tomcat's standard HTML, text, CSS, JavaScript and JSON types.

Tomcat negotiates gzip using `Accept-Encoding` and sets `Vary: Accept-Encoding`; clients that do not accept gzip receive identity responses. Use `on`, rather than `force`, to retain the MIME and size checks. **For streamed SRU responses with unknown Content-Length, Tomcat may compress even below the configured minimum.** The threshold is not a guarantee for streaming output; enforcing one would require buffering data first. Compression reduces transferred bytes, not Solr processing time. See the [HTTP connector settings](https://tomcat.apache.org/tomcat-9.0-doc/config/http.html) and [environment property replacement](https://tomcat.apache.org/tomcat-9.0-doc/config/systemprops.html#Property_replacements).

If compression is managed centrally by the OpenShift ingress/router, set `DZP_FCS_HTTP_COMPRESSION=off` for this application. Keep one clearly defined compression policy. No cluster-wide ingress configuration is changed by this image.

Application log field `responseBytes` is measured before connector compression. It records uncompressed application bytes, not gzip transfer size.

## Environment variables

| Variable | Description | Default value |
|---|---|---|
| DZP_FCS_SOLR_ENDPOINT | Endpoint URL for the German Newspaper Portal Solr search engine | `https://api.deutsche-digitale-bibliothek.de/2/search/index/newspaper-issues/select` |
| DZP_FCS_CLIENT_ID | Application identifier sent in the `User-Agent` header (HTTP product token) | `dzp-fcs` |
| DZP_FCS_CONTACT_EMAIL | Maintainer email sent in the `User-Agent` comment | `m.buechner@dnb.de` |
| DZP_FCS_CONTACT_URL | Project or contact URL sent in the `User-Agent` comment | `https://github.com/mbuechner/dzp-fcs` |
| DZP_FCS_BACKEND_CONNECT_TIMEOUT_SECONDS | Connection timeout for an individual connection attempt | `10` |
| DZP_FCS_BACKEND_READ_TIMEOUT_SECONDS | Maximum inactivity while reading a backend response | `50` |
| DZP_FCS_BACKEND_REQUEST_TIMEOUT_SECONDS | Total backend budget per search, including queueing, all attempts, response-body reads and retry delays | `50` |
| DZP_FCS_BACKEND_MAX_ATTEMPTS | Maximum application-level attempts, including the initial attempt; `1` disables application-level retries | `3` |
| DZP_FCS_BACKEND_MAX_CONCURRENT_REQUESTS | Maximum simultaneous synchronous backend calls per application instance, including response-body reads | `16` |
| DZP_FCS_BACKEND_RETRY_DELAY_MILLIS | Initial exponential retry delay, with jitter; `0` disables backoff | `1000` |
| DZP_FCS_BACKEND_MAX_RETRY_DELAY_MILLIS | Upper bound for client-generated backoff; must be at least the initial delay | `10000` |

Every request to the DDB API includes an identification header:

```http
User-Agent: dzp-fcs/1.5 (+https://github.com/mbuechner/dzp-fcs; mailto:m.buechner@dnb.de)
```

Environment variables override the bundled defaults. Maven injects the project version from `pom.xml` into `dzp-fcs.properties` during the build, so the `User-Agent` version follows each release automatically. The identification follows the standard HTTP [User-Agent](https://www.rfc-editor.org/rfc/rfc9110.html#name-user-agent) header; the DDB search endpoint does not document a separate client-ID parameter. Client IDs must be HTTP product tokens without spaces; contact values must be non-empty HTTP header values.

## Backend timeouts and retries

The default 50-second backend budget leaves approximately ten seconds for parsing and returning the SRU response behind an OpenShift route configured with `haproxy.router.openshift.io/timeout: 60s`. It is one budget for the entire backend operation, not a new timeout for every retry. Waiting for a concurrency permit and reading the complete response body also consume this budget. Parsing and SRU serialization happen afterwards; this setting does not impose an end-to-end HTTP deadline.

For example, the OpenShift deployment can override these values using environment variables:

```yaml
env:
  - name: DZP_FCS_BACKEND_REQUEST_TIMEOUT_SECONDS
    value: "50"
  - name: DZP_FCS_BACKEND_READ_TIMEOUT_SECONDS
    value: "50"
  - name: DZP_FCS_BACKEND_CONNECT_TIMEOUT_SECONDS
    value: "10"
  - name: DZP_FCS_BACKEND_MAX_ATTEMPTS
    value: "3"
```

GET requests can be retried after HTTP 408, 429, 500, 502, 503 or 504 and transient transport failures. Other HTTP errors, certificate/protocol failures, cancellation and interruption are not retried. Application-level retries also exclude socket timeouts: a missing response may mean the backend is still computing, and repeating that search could add more load. OkHttp can still recover an early connection failure internally. Responses are closed before retrying, and retry delays do not occupy a concurrency permit. `Retry-After` accepts seconds and RFC 1123 HTTP dates: the application never retries earlier than requested by the backend. If that delay exceeds the remaining budget, or a non-empty header cannot be interpreted safely, it returns the failure instead. JSON parsing and mapping errors do not trigger another backend request.

`MAX_ATTEMPTS` counts application-level calls. OkHttp can additionally recover connections or perform HTTP follow-ups internally; those exchanges share the same remaining time budget. Retry warnings log attempt, failure type/status and timing without adding search terms or request URLs. Timeout and limit values must be positive integers; backoff values can also be zero. Invalid settings prevent startup with a message naming the relevant variable.

Keep the backend budget below the shortest timeout in the route and clients, with room for producing the response. Increasing an environment variable cannot extend an upstream timeout. A backend operation that cannot finish within the budget still returns an SRU system diagnostic; retries improve recovery from temporary failures but cannot guarantee success within 60 seconds.

## Servlet and corpus configuration

Settings in [`web.xml`](../src/main/webapp/WEB-INF/web.xml) are servlet/context parameters, not additional `DZP_FCS_*` environment variables. Changing the packaged descriptor or corpus files requires rebuilding the WAR.

| Parameter / resource | Bundled value / behavior |
|---|---|
| `DEFAULT_RESOURCE_PID` | `https://www.deutsche-digitale-bibliothek.de/newspaper`; must identify a root resource in the inventory |
| `de.ddb.labs.dzpfcs.resourceInventoryURL` | Optional filesystem path; otherwise [`WEB-INF/endpoint-description.xml`](../src/main/webapp/WEB-INF/endpoint-description.xml) |
| `eu.clarin.sru.server.numberOfRecords` | `250` default records per page |
| `eu.clarin.sru.server.maximumRecords` | `1000` maximum records per page |
| `eu.clarin.sru.server.allowOverrideMaximumRecords` | `true` |
| `eu.clarin.sru.server.allowOverrideIndentResponse` | `true` |
| `eu.clarin.sru.server.echoRequests` | `false`; avoids the upstream SRU-2.0 XCQL serializer issue |
| `eu.clarin.sru.server.sruSupportedVersionMax` | `2.0` |
| `eu.clarin.sru.server.legacyNamespaceMode` | `loc` |
| [`WEB-INF/sru-server-config.xml`](../src/main/webapp/WEB-INF/sru-server-config.xml) | Database metadata, supported index and record schema |

The public database, host, transport and port parameters in `web.xml` use the `DZP_FCS_PUBLIC_*` substitutions above. The resource inventory parameter retains its existing name even though implementation classes live in subpackages.

Only root resources are searchable. Endpoint inventory and backend configuration are validated at servlet startup. The default request encoding is UTF-8; an explicitly supplied request charset takes precedence. See [protocol behavior and limitations](development.md#sru-and-fcs-responses).
