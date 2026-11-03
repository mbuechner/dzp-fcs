# Upstream issue drafts

[Overview](../README.md) · [SRU/FCS behavior and limitations](development.md#sru-and-fcs-responses)

These are unsent issue drafts recovered from the earlier implementation review. They record observations against the dependency versions below; they do not establish the current status of upstream development.

Tested dependency chain: `eu.clarin.sru.fcs:fcs-simple-endpoint:2.1.1` → `eu.clarin.sru:sru-server:1.14.0`, Java 25, Tomcat 9 and Woodstox 7.3.0. The first three defects were observed in the transitive SRU server dependency, rather than the endpoint's search engine.

## 1. String record escaping produces malformed XML

**Repository:** [clarin-eric/fcs-sru-server](https://github.com/clarin-eric/fcs-sru-server)

With `sru-server` 1.14.0, a search returning at least one record produces malformed XML when string escaping is requested:

```http
GET /?version=1.2&operation=searchRetrieve&query=test&recordPacking=string
GET /?query=test&recordXMLEscaping=string
```

**Observed:** The outer start tag is serialized as `<sru:recordData&gt;`, or the SRU 2.0 equivalent, so the response cannot be parsed as XML. The defect also occurs with SRU 2.0 `recordPacking=packed` and `recordPacking=unpacked`.

**Expected:** A well-formed response with only the record payload escaped.

`SRUXMLStreamWriter.startRecord()` appears to enable escaping while the outer start tag is still buffered. The proposed issue requests serializer regression tests for both SRU generations and multiple records.

**Source:** [SRUXMLStreamWriter.java, SRUServer-1.14.0](https://github.com/clarin-eric/fcs-sru-server/blob/SRUServer-1.14.0/src/main/java/eu/clarin/sru/server/SRUXMLStreamWriter.java#L386).

## 2. SRU 2.0 echoed XCQL does not validate against the OASIS schema

**Repository:** [clarin-eric/fcs-sru-server](https://github.com/clarin-eric/fcs-sru-server)

With `sru-server` 1.14.0 and `echoRequests=true`, the upstream default, request a native SRU 2.0 search without a `version` parameter:

```http
GET /?query=Berlin&maximumRecords=1
```

Validate the response against the official OASIS `sruResponse.xsd` and `xcql.xsd`.

**Observed:** `sruResponse:xQuery` contains `xcql:searchClause` directly. The XSD requires `xcqlDefinition: prefixes?, triple, sortKeys?`. Validation fails even when the returned record payload is valid. The serializer appears to reuse the legacy XCQL structure with the SRU 2.0 namespace.

**Expected:** Schema-valid SRU 2.0 XCQL, including simple and Boolean queries.

**Workaround used by this endpoint:** Set `eu.clarin.sru.server.echoRequests=false`, since the echo is optional.

**Sources:** [SRUServer.java, SRUServer-1.14.0](https://github.com/clarin-eric/fcs-sru-server/blob/SRUServer-1.14.0/src/main/java/eu/clarin/sru/server/SRUServer.java#L1041) and [OASIS XCQL schema](https://docs.oasis-open.org/search-ws/searchRetrieve/v1.0/os/schemas/xcql.xsd).

## 3. Support the required application/sru+xml response media type in SRU 2.0

**Repository:** [clarin-eric/fcs-sru-server](https://github.com/clarin-eric/fcs-sru-server)

A native SRU 2.0 search with `sru-server` 1.14.0 returns `Content-Type: application/xml;charset=utf-8`:

```http
GET /?query=Berlin
```

**Observed:** `SRUServer.RESPONSE_CONTENT_TYPE` is hardcoded to `application/xml`.

OASIS SRU 2.0 section 15.2.3 requires support for `application/sru+xml`; section 13.4 and Appendix C define it as the default response media type.

**Expected:** Support `application/sru+xml` for SRU 2.0 and use it by default, while preserving the appropriate behavior for legacy SRU versions. The proposed issue requests HTTP-level tests for the response `Content-Type` and media-type negotiation; XML well-formedness alone does not catch this discrepancy.

**Sources:** [SRUServer.java, SRUServer-1.14.0](https://github.com/clarin-eric/fcs-sru-server/blob/SRUServer-1.14.0/src/main/java/eu/clarin/sru/server/SRUServer.java#L56) and [OASIS SRU 2.0 specification](https://docs.oasis-open.org/search-ws/searchRetrieve/v1.0/os/part3-sru2.0/searchRetrieve-v1.0-os-part3-sru2.0.html).

## 4. Align HITS and ResourceFragment cardinalities between the specification and XSDs

**Repository:** [clarin-eric/fcs-misc](https://github.com/clarin-eric/fcs-misc)

The earlier review identified two cardinality differences between the FCS Core 2.0 prose and the schemas pinned at commit `dbc795dc5cc1a6e7ecc52bda3fcaa1fdefeccc48`:

- The prose requires a DataView inside `ResourceFragment` and HITS for every normal result, but `Resource.xsd` permits zero DataViews in a fragment.
- The prose describes the Hit marker as recommended rather than schema-enforced, but `DataView-Hits.xsd` declares `Hit` without `minOccurs="0"`, requiring at least one Hit. A plain-text Result therefore fails XSD validation.

**Proposed issue:** Clarify the intended behavior, align the prose and XSDs, and add conformance examples for missing highlighting, plain-text snippets and empty hits.

**Behavior used by this endpoint:** Return a surrogate diagnostic when no genuine non-empty hit can be represented, rather than inventing a Hit.

**Pinned sources:** [Resource schema](https://github.com/clarin-eric/fcs-misc/blob/dbc795dc5cc1a6e7ecc52bda3fcaa1fdefeccc48/fcs-core-2.0/attachments/Resource.xsd#L68) and [HITS schema](https://github.com/clarin-eric/fcs-misc/blob/dbc795dc5cc1a6e7ecc52bda3fcaa1fdefeccc48/fcs-core-2.0/attachments/DataView-Hits.xsd#L38). The vendored schema source revision and checksums are recorded in [PROVENANCE.txt](../src/test/resources/fcs20/PROVENANCE.txt). This draft makes no claim about later schema revisions.
