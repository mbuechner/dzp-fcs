# SRU/CQL FCS 2.0 Endpoint for German Newspaper Portal

This application implements a [Federated Content Search (FCS)](https://www.clarin.eu/content/federated-content-search-clarin-fcs-technical-details) endpoint for the [German Newspaper Portal](https://www.deutsche-digitale-bibliothek.de/newspaper).

The aim of Federated Content Search is to enable a content search via distributed resources. The [German Digital Library](https://www.deutsche-digitale-bibliothek.de/) provides the German Newspaper Portal with a searchable corpus for this purpose.

The endpoint is published at the following URL: https://labs.deutsche-digitale-bibliothek.de/app/dzp-fcs

The following searches are given as examples.

* Search for ["Berlin"](https://labs.deutsche-digitale-bibliothek.de/app/dzp-fcs?operation=searchRetrieve&query=Berlin)
* Search for ["Berlin ist schön"](https://labs.deutsche-digitale-bibliothek.de/app/dzp-fcs?operation=searchRetrieve&query="Berlin%20ist%20schön")
* Search for ["Berlin" and "Hamburg"](https://labs.deutsche-digitale-bibliothek.de/app/dzp-fcs?operation=searchRetrieve&query=Berlin%20AND%20Hamburg)

The endpoint is used for:

* Text+: [https://fcs.text-plus.org/?&query="Berlin ist schön"](https://fcs.text-plus.org/?&query="Berlin%20ist%20schön")
* Text+: [https://text-plus.org/en/](https://text-plus.org/en/) (Search → Content)
* SAW Leipzig: https://tppssi-demo.saw-leipzig.de/ (Search in content)
* etc.

## Implementation
This Java servlet was implemented using the [FCS Endpoint Archetype](https://github.com/clarin-eric/fcs-endpoint-archetype). Further information can be found there.

## Build

Building requires JDK 25 LTS and Apache Maven. CI and the Docker build use Eclipse Temurin 25.

The build automation tool "Maven" can be used to create the Web Application Archive (WAR). The following command, executed in the folder containing the `pom.xml` file, creates a publishable WAR file.

```bash
mvn [clean] package
```

## Docker
Yes, there's a docker container for this application available at GitHub.

https://github.com/mbuechner/dzp-fcs/pkgs/container/dzp-fcs

The container runs Apache Tomcat 9.0.123 on Java 25. The FCS endpoint uses `javax.servlet` (Servlet 4.0), so Tomcat 10 or 11 requires migrating the application and its servlet dependencies to `jakarta.servlet`. See the [Tomcat migration guide](https://tomcat.apache.org/migration-10.html).

The Tomcat project has announced the end of support for Tomcat 9.0 on 31 March 2027 and a compatible Tomcat 9.1 branch with releases until 31 December 2030. See the [Tomcat support announcement](https://tomcat.apache.org/tomcat-9.0.x-eos).

### Container build

1. Checkout GitHub repository:

   ```bash
   git clone https://github.com/mbuechner/dzp-fcs
   ```

2. Go into folder:

   ```bash
   cd dzp-fcs
   ```

3. Build the container:

   ```bash
   docker build -t dzp-fcs .
   ```

4. Start the container:

   ```bash
   docker run --rm -p 8080:8080 dzp-fcs
   ```

5. Open browser: [http://localhost:8080/](http://localhost:8080/)


### Environment variables

| Variable | Description | Default value |
|---|---|---|
| DZP_FCS_SOLR_ENDPOINT | Endpoint URL for the German Newspaper Portal Solr search engine | `https://api.deutsche-digitale-bibliothek.de/2/search/index/newspaper-issues/select` |
| DZP_FCS_CLIENT_ID | Application identifier sent in the `User-Agent` header (HTTP product token) | `dzp-fcs` |
| DZP_FCS_CONTACT_EMAIL | Maintainer email sent in the `User-Agent` comment | `m.buechner@dnb.de` |
| DZP_FCS_CONTACT_URL | Project or contact URL sent in the `User-Agent` comment | `https://github.com/mbuechner/dzp-fcs` |

Both the count request and the result request to the DDB API include an identification header:

```http
User-Agent: dzp-fcs/1.5 (+https://github.com/mbuechner/dzp-fcs; mailto:m.buechner@dnb.de)
```

Environment variables override the bundled defaults. Maven injects the project version from `pom.xml` into `dzp-fcs.properties` during the build, so the `User-Agent` version follows each release automatically. The identification follows the standard HTTP [User-Agent](https://www.rfc-editor.org/rfc/rfc9110.html#name-user-agent) header; the DDB search endpoint does not document a separate client-ID parameter. Client IDs must be HTTP product tokens without spaces; contact values must be non-empty HTTP header values.
