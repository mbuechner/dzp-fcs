package de.ddb.labs.dzpfcs.endpoint;

import de.ddb.labs.dzpfcs.model.Results;
import de.ddb.labs.dzpfcs.model.ResultsEntry;
import eu.clarin.sru.server.SRUConstants;
import eu.clarin.sru.server.SRUDiagnostic;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import javax.xml.transform.Source;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DzpSRUSearchResultSetTest {

    private static final String DIAGNOSTIC_NS = "http://www.loc.gov/zing/srw/diagnostic/";

    @Test
    void emptyHighlightsKeepTheResultPositionAsASurrogateInsteadOfAnEmptyResource() throws Exception {
        ResultsEntry entry = entry("01234567890123456789012345678901", List.of());
        DzpSRUSearchResultSet resultSet = resultSet(List.of(entry), 7);

        Document document = serialize(resultSet);

        assertEquals(1, resultSet.getRecordCount());
        assertEquals(7, resultSet.getTotalRecordCount());
        assertEquals(1, document.getElementsByTagName("record").getLength());
        assertEquals(0, document.getElementsByTagNameNS(DzpConstants.CLARIN_FCS_RECORD_SCHEMA, "Resource").getLength());
        NodeList fragments = document.getElementsByTagNameNS(DzpConstants.CLARIN_FCS_RECORD_SCHEMA, "ResourceFragment");
        assertEquals(0, fragments.getLength());
        assertEquals(0, document.getElementsByTagNameNS(DzpConstants.CLARIN_FCS_RECORD_SCHEMA, "DataView").getLength());
        assertEquals(0, document.getElementsByTagNameNS(DzpConstants.FCS_HITS_NS, "Result").getLength());
        assertEquals(SRUConstants.SRU_RECORD_NOT_AVAILABLE_IN_THIS_SCHEMA,
                document.getElementsByTagNameNS(DIAGNOSTIC_NS, "uri").item(0).getTextContent());
        assertEquals(DzpConstants.CLARIN_FCS_RECORD_SCHEMA,
                document.getElementsByTagNameNS(DIAGNOSTIC_NS, "details").item(0).getTextContent());
    }

    @Test
    void aSnippetStillProducesANamespacedHitsDataView() throws Exception {
        ResultsEntry entry = entry("01234567890123456789012345678901", List.of("Vor <Hit>Berlin</Hit> & danach"));
        DzpSRUSearchResultSet resultSet = resultSet(List.of(entry), 1);

        Document document = serialize(resultSet);

        NodeList dataViews = document.getElementsByTagNameNS(DzpConstants.CLARIN_FCS_RECORD_SCHEMA, "DataView");
        assertEquals(1, dataViews.getLength());
        assertEquals(DzpConstants.FCS_HITS_MIMETYPE, ((Element) dataViews.item(0)).getAttribute("type"));
        NodeList results = document.getElementsByTagNameNS(DzpConstants.FCS_HITS_NS, "Result");
        assertEquals(1, results.getLength());
        assertEquals("Vor Berlin & danach", results.item(0).getTextContent());
        NodeList hits = document.getElementsByTagNameNS(DzpConstants.FCS_HITS_NS, "Hit");
        assertEquals(1, hits.getLength());
        assertEquals("Berlin", hits.item(0).getTextContent());
    }

    @Test
    void snippetWhitespaceIsPreservedAroundHits() throws Exception {
        ResultsEntry entry = entry("01234567890123456789012345678901", List.of(" \t<Hit>Berlin</Hit>\n "));
        DzpSRUSearchResultSet resultSet = resultSet(List.of(entry), 1);

        Document document = serialize(resultSet);

        NodeList results = document.getElementsByTagNameNS(DzpConstants.FCS_HITS_NS, "Result");
        assertEquals(1, results.getLength());
        assertEquals(" \tBerlin\n ", results.item(0).getTextContent());
    }

    @Test
    void missingSnippetsDoNotRemoveRecordsFromAMixedResultSet() throws Exception {
        ResultsEntry first = entry("01234567890123456789012345678901", List.of());
        ResultsEntry second = entry("11234567890123456789012345678901", List.of("<Hit>Berlin</Hit>"));
        ResultsEntry third = entry("21234567890123456789012345678901", List.of());
        DzpSRUSearchResultSet resultSet = resultSet(List.of(first, second, third), 17);

        Document document = serialize(resultSet);

        assertEquals(3, resultSet.getRecordCount());
        assertEquals(17, resultSet.getTotalRecordCount());
        assertEquals(3, document.getElementsByTagName("record").getLength());
        assertEquals(2, document.getElementsByTagNameNS(DIAGNOSTIC_NS, "diagnostic").getLength());
        assertEquals(1, document.getElementsByTagNameNS(DzpConstants.CLARIN_FCS_RECORD_SCHEMA, "Resource").getLength());
        NodeList fragments = document.getElementsByTagNameNS(DzpConstants.CLARIN_FCS_RECORD_SCHEMA, "ResourceFragment");
        assertEquals(1, fragments.getLength());
        assertEquals(second.getId(), ((Element) fragments.item(0)).getAttribute("pid"));
        NodeList records = document.getElementsByTagName("record");
        assertEquals(1, ((Element) records.item(0)).getElementsByTagNameNS(DIAGNOSTIC_NS, "diagnostic").getLength());
        assertEquals(1, ((Element) records.item(1)).getElementsByTagNameNS(DzpConstants.FCS_HITS_NS, "Hit").getLength());
        assertEquals(1, ((Element) records.item(2)).getElementsByTagNameNS(DIAGNOSTIC_NS, "diagnostic").getLength());
    }

    @Test
    void onlyTheFirstSnippetAndFirstHitAreRendered() throws Exception {
        ResultsEntry entry = entry("01234567890123456789012345678901",
                List.of("<Hit>Berlin</Hit> & <Hit>Hamburg</Hit>", "<Hit>unused"));

        Document document = serialize(resultSet(List.of(entry), 1));

        assertEquals(1, document.getElementsByTagNameNS(DzpConstants.FCS_HITS_NS, "Result").getLength());
        assertEquals("Berlin & Hamburg", document.getElementsByTagNameNS(DzpConstants.FCS_HITS_NS, "Result").item(0).getTextContent());
        NodeList hits = document.getElementsByTagNameNS(DzpConstants.FCS_HITS_NS, "Hit");
        assertEquals(1, hits.getLength());
        assertEquals("Berlin", hits.item(0).getTextContent());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "plain text", "\u0000\uD800\uFFFE\uDC00\uFFFF", "<Hit></Hit>",
        "<Hit>\u0000\uD800\uFFFE\uDC00\uFFFF</Hit>", "<Hit><Hit></Hit>later</Hit>",
        "<Hit></Hit><Hit>later actual hit</Hit>", "<Hit> \t\r\n </Hit>", "<Hit>\u2003</Hit>",
        "</Hit><Hit>reversed", "<Hit>unclosed", "<Hit>x\u0001</Hit>"})
    void unrepresentableFirstSnippetsBecomeSurrogatesEvenWhenLaterSnippetsAreUsable(String snippet) throws Exception {
        ResultsEntry entry = entry("01234567890123456789012345678901", List.of(snippet, "<Hit>later snippet</Hit>"));
        DzpSRUSearchResultSet resultSet = resultSet(List.of(entry), 53);

        Document document = serialize(resultSet);

        assertEquals(1, resultSet.getRecordCount());
        assertEquals(53, resultSet.getTotalRecordCount());
        assertEquals(1, document.getElementsByTagName("record").getLength());
        assertEquals(1, document.getElementsByTagNameNS(DIAGNOSTIC_NS, "diagnostic").getLength());
        assertEquals(0, document.getElementsByTagNameNS(DzpConstants.CLARIN_FCS_RECORD_SCHEMA, "Resource").getLength());
        assertEquals(0, document.getElementsByTagNameNS(DzpConstants.FCS_HITS_NS, "Hit").getLength());
    }

    @Test
    void surrogateDecisionIsStableAndResetsWhenAdvancingToTheNextRecord() throws Exception {
        DzpSRUSearchResultSet resultSet = resultSet(List.of(
                entry("01234567890123456789012345678901", List.of()),
                entry("11234567890123456789012345678901", List.of("<Hit>Berlin</Hit>"))), 17);

        assertTrue(resultSet.nextRecord());
        SRUDiagnostic unavailable = resultSet.getSurrogateDiagnostic();
        assertNotNull(unavailable);
        assertEquals(SRUConstants.SRU_RECORD_NOT_AVAILABLE_IN_THIS_SCHEMA, unavailable.getURI());
        assertSame(unavailable, resultSet.getSurrogateDiagnostic());
        assertTrue(resultSet.nextRecord());
        assertNull(resultSet.getSurrogateDiagnostic());
        assertEquals(2, resultSet.getRecordCount());
        assertEquals(17, resultSet.getTotalRecordCount());
    }

    @Test
    void directPayloadCallsCannotBypassTheSurrogateGuardAndWriteAnEmptyResource() throws Exception {
        DzpSRUSearchResultSet resultSet = resultSet(List.of(
                entry("01234567890123456789012345678901", List.of("plain text"))), 1);
        assertTrue(resultSet.nextRecord());
        StringWriter output = new StringWriter();
        XMLStreamWriter writer = XMLOutputFactory.newFactory().createXMLStreamWriter(output);
        try {
            writer.writeStartElement("root");
            writer.flush();
            String before = output.toString();

            assertThrows(XMLStreamException.class, () -> resultSet.writeRecord(writer));

            writer.flush();
            assertEquals(before, output.toString());
            writer.writeEndElement();
        } finally {
            writer.close();
        }
    }

    @Test
    void everyNormalRecordPassesTheOfficialOfflineResourceAndHitsSchemas() throws Exception {
        Document document = serialize(resultSet(List.of(
                entry("01234567890123456789012345678901", List.of()),
                entry("11234567890123456789012345678901", List.of("<Hit></Hit>")),
                entry("21234567890123456789012345678901", List.of("Before <Hit>Berlin & \uD83D\uDE80</Hit> after <tag>literal</tag>"))), 53));

        SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        try (InputStream resource = getClass().getResourceAsStream("/fcs20/Resource.xsd");
                InputStream hits = getClass().getResourceAsStream("/fcs20/DataView-Hits.xsd")) {
            assertNotNull(resource);
            assertNotNull(hits);
            var schema = factory.newSchema(new Source[]{new StreamSource(resource), new StreamSource(hits)});
            NodeList resources = document.getElementsByTagNameNS(DzpConstants.CLARIN_FCS_RECORD_SCHEMA, "Resource");
            assertEquals(1, resources.getLength());
            for (int i = 0; i < resources.getLength(); i++) {
                schema.newValidator().validate(new DOMSource(resources.item(i)));
            }
            assertEquals(2, document.getElementsByTagNameNS(DIAGNOSTIC_NS, "diagnostic").getLength());
        }
    }

    @ParameterizedTest
    @MethodSource("malformedIdentifiers")
    void malformedIdentifiersBecomeSurrogatesBeforeAnyPayloadIsWritten(String id) throws Exception {
        DzpSRUSearchResultSet resultSet = resultSet(List.of(entry(id, List.of("<Hit>Berlin</Hit>"))), 53);
        assertTrue(resultSet.nextRecord());
        assertEquals(SRUConstants.SRU_RECORD_NOT_AVAILABLE_IN_THIS_SCHEMA, resultSet.getSurrogateDiagnostic().getURI());
        assertEquals(1, resultSet.getRecordCount());
        assertEquals(53, resultSet.getTotalRecordCount());

        StringWriter output = new StringWriter();
        XMLStreamWriter writer = XMLOutputFactory.newFactory().createXMLStreamWriter(output);
        try {
            writer.writeStartElement("root");
            writer.flush();
            String before = output.toString();
            assertThrows(XMLStreamException.class, () -> resultSet.writeRecord(writer));
            writer.flush();
            assertEquals(before, output.toString());
            writer.writeEndElement();
        } finally {
            writer.close();
        }
    }

    static Stream<String> malformedIdentifiers() {
        String item = "01234567890123456789012345678901";
        return Stream.of(null, "short", " ".repeat(32), item + "\u0000", item + "\u0001",
                item + "\uFFFE", item + "\uD800", "a".repeat(31) + "\uD83D\uDE80");
    }

    @Test
    void aBadIdentifierDoesNotPreventFollowingRecordsFromBeingSerialized() throws Exception {
        Document document = serialize(resultSet(List.of(
                entry("short", List.of("<Hit>unusable link</Hit>")),
                entry("11234567890123456789012345678901", List.of("<Hit>Berlin</Hit>"))), 53));

        assertEquals(2, document.getElementsByTagName("record").getLength());
        assertEquals(1, document.getElementsByTagNameNS(DIAGNOSTIC_NS, "diagnostic").getLength());
        assertEquals("Berlin", document.getElementsByTagNameNS(DzpConstants.FCS_HITS_NS, "Hit").item(0).getTextContent());
    }

    @Test
    void separateRequestsCanSerializeTheirOwnDtosAndCursorsConcurrently() throws Exception {
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> serialize(resultSet(List.of(
                    entry("01234567890123456789012345678901", List.of("<Hit>Berlin</Hit>"))), 7)));
            var second = workers.submit(() -> serialize(resultSet(List.of(
                    entry("11234567890123456789012345678901", List.of()),
                    entry("21234567890123456789012345678901", List.of("<Hit>Hamburg</Hit>"))), 17)));

            Document firstDocument = first.get();
            Document secondDocument = second.get();
            assertEquals("Berlin", firstDocument.getElementsByTagNameNS(DzpConstants.FCS_HITS_NS, "Hit").item(0).getTextContent());
            assertEquals(0, firstDocument.getElementsByTagNameNS(DIAGNOSTIC_NS, "diagnostic").getLength());
            assertEquals("Hamburg", secondDocument.getElementsByTagNameNS(DzpConstants.FCS_HITS_NS, "Hit").item(0).getTextContent());
            assertEquals(1, secondDocument.getElementsByTagNameNS(DIAGNOSTIC_NS, "diagnostic").getLength());
        }
    }

    @Test
    void aCursorOutsideThePageCannotReuseThePreviousRecord() throws Exception {
        DzpSRUSearchResultSet resultSet = resultSet(List.of(
                entry("01234567890123456789012345678901", List.of("<Hit>Berlin</Hit>"))), 1);

        assertThrows(NoSuchElementException.class, resultSet::getSurrogateDiagnostic);
        assertTrue(resultSet.nextRecord());
        assertNull(resultSet.getSurrogateDiagnostic());
        assertFalse(resultSet.nextRecord());
        assertFalse(resultSet.nextRecord());
        assertThrows(NoSuchElementException.class, resultSet::getSurrogateDiagnostic);
    }

    private static ResultsEntry entry(String id, List<String> snippets) {
        ResultsEntry entry = new ResultsEntry();
        entry.setId(id);
        entry.setPagenumber("1");
        entry.setPlainpagefulltext(snippets);
        return entry;
    }

    private static DzpSRUSearchResultSet resultSet(List<ResultsEntry> entries, long total) {
        Results results = new Results("https://example.org/corpus", "Berlin", entries, total, 0);
        return new DzpSRUSearchResultSet(null, (uri, details, message) -> {
            throw new AssertionError("Unexpected SRU diagnostic: " + uri);
        }, results);
    }

    private static Document serialize(DzpSRUSearchResultSet resultSet) throws Exception {
        StringWriter output = new StringWriter();
        XMLStreamWriter writer = XMLOutputFactory.newFactory().createXMLStreamWriter(output);
        try {
            writer.writeStartDocument();
            writer.writeStartElement("records");
            while (resultSet.nextRecord()) {
                // Mirror the framework's surrogate hook without reimplementing
                // SRU transport or its version-specific diagnostic envelope.
                writer.writeStartElement("record");
                SRUDiagnostic surrogate = resultSet.getSurrogateDiagnostic();
                if (surrogate == null) {
                    resultSet.writeRecord(writer);
                } else {
                    writer.writeStartElement("diag", "diagnostic", DIAGNOSTIC_NS);
                    writer.writeNamespace("diag", DIAGNOSTIC_NS);
                    writer.writeStartElement("diag", "uri", DIAGNOSTIC_NS);
                    writer.writeCharacters(surrogate.getURI());
                    writer.writeEndElement();
                    writer.writeStartElement("diag", "details", DIAGNOSTIC_NS);
                    writer.writeCharacters(surrogate.getDetails());
                    writer.writeEndElement();
                    writer.writeEndElement();
                }
                writer.writeEndElement();
            }
            writer.writeEndElement();
            writer.writeEndDocument();
        } finally {
            writer.close();
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(output.toString())));
    }
}
