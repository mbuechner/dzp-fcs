package de.ddb.labs.dzpfcs.endpoint;

import de.ddb.labs.dzpfcs.model.Results;
import de.ddb.labs.dzpfcs.model.ResultsEntry;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamWriter;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DzpSRUSearchResultSetTest {

    @Test
    void emptyHighlightsKeepTheRecordWithoutAHitsDataView() throws Exception {
        ResultsEntry entry = entry("01234567890123456789012345678901", List.of());
        DzpSRUSearchResultSet resultSet = resultSet(List.of(entry), 7);

        Document document = serialize(resultSet);

        assertEquals(1, resultSet.getRecordCount());
        assertEquals(7, resultSet.getTotalRecordCount());
        assertEquals(1, document.getElementsByTagNameNS(DzpConstants.CLARIN_FCS_RECORD_SCHEMA, "Resource").getLength());
        NodeList fragments = document.getElementsByTagNameNS(DzpConstants.CLARIN_FCS_RECORD_SCHEMA, "ResourceFragment");
        assertEquals(1, fragments.getLength());
        assertEquals(entry.getId(), ((Element) fragments.item(0)).getAttribute("pid"));
        assertEquals(0, document.getElementsByTagNameNS(DzpConstants.CLARIN_FCS_RECORD_SCHEMA, "DataView").getLength());
        assertEquals(0, document.getElementsByTagNameNS(DzpConstants.FCS_HITS_NS, "Result").getLength());
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
        assertEquals(3, document.getElementsByTagNameNS(DzpConstants.CLARIN_FCS_RECORD_SCHEMA, "Resource").getLength());
        NodeList fragments = document.getElementsByTagNameNS(DzpConstants.CLARIN_FCS_RECORD_SCHEMA, "ResourceFragment");
        assertEquals(3, fragments.getLength());
        assertEquals(first.getId(), ((Element) fragments.item(0)).getAttribute("pid"));
        assertEquals(second.getId(), ((Element) fragments.item(1)).getAttribute("pid"));
        assertEquals(third.getId(), ((Element) fragments.item(2)).getAttribute("pid"));
        assertEquals(0, ((Element) fragments.item(0)).getElementsByTagNameNS(DzpConstants.CLARIN_FCS_RECORD_SCHEMA, "DataView").getLength());
        assertEquals(1, ((Element) fragments.item(1)).getElementsByTagNameNS(DzpConstants.CLARIN_FCS_RECORD_SCHEMA, "DataView").getLength());
        assertEquals(0, ((Element) fragments.item(2)).getElementsByTagNameNS(DzpConstants.CLARIN_FCS_RECORD_SCHEMA, "DataView").getLength());
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
        return new DzpSRUSearchResultSet(null, null, (uri, details, message) -> {
            throw new AssertionError("Unexpected SRU diagnostic: " + uri);
        }, List.of(), results);
    }

    private static Document serialize(DzpSRUSearchResultSet resultSet) throws Exception {
        StringWriter output = new StringWriter();
        XMLStreamWriter writer = XMLOutputFactory.newFactory().createXMLStreamWriter(output);
        try {
            writer.writeStartDocument();
            writer.writeStartElement("records");
            while (resultSet.nextRecord()) {
                resultSet.writeRecord(writer);
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
