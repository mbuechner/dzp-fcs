# dzp-fcs

An SRU/CQL [Federated Content Search](https://www.clarin.eu/content/federated-content-search-clarin-fcs-technical-details) endpoint for the [German Newspaper Portal](https://www.deutsche-digitale-bibliothek.de/newspaper). Translates CQL into Solr searches and returns paginated FCS 2.0 records with highlighted text. Supports terms, phrases and basic `AND`/`OR` queries.

The [German Digital Library](https://www.deutsche-digitale-bibliothek.de/) provides the searchable newspaper corpus. FCS lets clients search it alongside resources from other providers.

**Stack:** Java 25 · Maven · Servlet 4 / Tomcat 9 · CLARIN FCS · OkHttp · Jackson · Log4j JSON logs.

**Live:** [labs.deutsche-digitale-bibliothek.de/app/dzp-fcs](https://labs.deutsche-digitale-bibliothek.de/app/dzp-fcs)

## Examples

Direct searches against the public endpoint:

| Search | Example query |
|---|---|
| Term | [Berlin](https://labs.deutsche-digitale-bibliothek.de/app/dzp-fcs?operation=searchRetrieve&query=Berlin) |
| Phrase | ["Berlin ist schön"](https://labs.deutsche-digitale-bibliothek.de/app/dzp-fcs?operation=searchRetrieve&query=%22Berlin%20ist%20sch%C3%B6n%22) |
| Boolean AND | [Berlin AND Hamburg](https://labs.deutsche-digitale-bibliothek.de/app/dzp-fcs?operation=searchRetrieve&query=Berlin%20AND%20Hamburg) |

Example integrations from the original project documentation:

- [Text+ FCS Aggregator: "Berlin ist schön"](https://fcs.text-plus.org/?query=%22Berlin%20ist%20sch%C3%B6n%22).
- [Text+ website](https://text-plus.org/en/): Search → Federated Content Search (FCS).
- [SAW Leipzig demo](https://tppssi-demo.saw-leipzig.de/): content search.

## Run

From a [source checkout](docs/development.md#get-the-sources):

```bash
docker build -t dzp-fcs .
docker run --rm -p 8080:8080 dzp-fcs
```

The endpoint listens at `http://localhost:8080/`. GZIP is negotiated automatically.

```bash
curl --compressed 'http://localhost:8080/?operation=explain'
curl --compressed --get 'http://localhost:8080/' \
  --data-urlencode 'operation=searchRetrieve' \
  --data-urlencode 'query="Berlin ist schön"' \
  --data-urlencode 'maximumRecords=10'
```

Omit `version` for native SRU 2.0; use `version=1.2` for SRU 1.2. Responses use embedded XML records. Unsupported CQL features return SRU diagnostics.

## Build

Requires JDK 25 and Maven:

```bash
mvn -B package              # tests + target/dzp-fcs.war
mvn -B spotbugs:check       # static analysis after compilation
```

The WAR uses `javax.servlet`; Tomcat 10/11 requires Jakarta migration. The Docker build supplies the Java/Maven toolchain. [Development notes](docs/development.md) cover packages, checks and protocol limitations.

## Configure

Pass environment variables with `docker run -e`, `--env-file`, or OpenShift `env`. Java environment variables override defaults in [`src/main/resources/.env`](src/main/resources/.env). **Container path, public URL and compression settings must be process environment variables**; the bundled Java `.env` does not configure Tomcat.

| Setting | Default / purpose |
|---|---|
| `DZP_FCS_CONTEXT_PATH` | `/` — actual Tomcat application path |
| `DZP_FCS_PUBLIC_PATH` | Context path — path advertised by Explain |
| `DZP_FCS_PUBLIC_HOST` | `localhost` — public hostname only |
| `DZP_FCS_PUBLIC_TRANSPORT` | `http` — public URL scheme |
| `DZP_FCS_PUBLIC_PORT` | `8080` — public port |
| `DZP_FCS_SOLR_ENDPOINT` | [DDB newspaper-issues Solr endpoint](https://api.deutsche-digitale-bibliothek.de/2/search/index/newspaper-issues/select) |
| `DZP_FCS_CLIENT_ID` | `dzp-fcs` — backend `User-Agent` product name |
| `DZP_FCS_CONTACT_EMAIL`, `DZP_FCS_CONTACT_URL` | Maintainer contact in `User-Agent`; [defaults](docs/configuration.md#environment-variables) |
| `DZP_FCS_BACKEND_*` | Connect/read/budget **10/50/50 s**, **3** attempts, **16** concurrent calls, backoff **1000–10000 ms** |
| `DZP_FCS_HTTP_COMPRESSION` | `on` — negotiated GZIP; `off` if the ingress handles compression |

All 19 variable names, exact defaults, retry rules and optional compression settings are in the [configuration reference](docs/configuration.md). The backend budget includes queueing, calls and retries; leave time for the response before the proxy deadline. Logs go to stdout as JSON Lines and include search text and request IDs.

For production, save this as `production.env`:

```dotenv
DZP_FCS_CONTEXT_PATH=/app/dzp-fcs
DZP_FCS_PUBLIC_HOST=labs.deutsche-digitale-bibliothek.de
DZP_FCS_PUBLIC_TRANSPORT=https
DZP_FCS_PUBLIC_PORT=443
```

```bash
docker run --rm -p 8080:8080 --env-file production.env dzp-fcs
```

This serves `/app/dzp-fcs` and publishes that path in Explain. If the proxy **strips the prefix**, use `DZP_FCS_CONTEXT_PATH=/` plus `DZP_FCS_PUBLIC_PATH=/app/dzp-fcs`. Context paths are selected at container startup; use a fresh container when changing them.

## Details

- [Configuration](docs/configuration.md): environment variables, paths, GZIP, timeouts, retries and servlet settings.
- [Development](docs/development.md): package map, tests, SRU/FCS behavior, known limitations and Solr tuning.
- [Upstream issue drafts](docs/upstream-issues.md): versioned reproductions and specification discrepancies.
- [Logging](docs/logging.md): events, timings and usage statistics.
- [Container registry](https://github.com/mbuechner/dzp-fcs/pkgs/container/dzp-fcs) · [License](LICENSE)
