package de.ddb.labs.dzpfcs.endpoint;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SnippetWriterTest {

    @Test
    void keepsEntitiesAndOtherTagsAsTextAndWritesOneNamespacedHit() throws Exception {
        Element root = serialize("before &amp; &#65; <em>literal</em> <Hit>A & B</Hit> after &lt;Hit&gt;");

        assertEquals("before &amp; &#65; <em>literal</em> A & B after &lt;Hit&gt;", root.getTextContent());
        assertEquals(1, root.getElementsByTagNameNS(DzpConstants.FCS_HITS_NS, "Hit").getLength());
        assertEquals("A & B", root.getElementsByTagNameNS(DzpConstants.FCS_HITS_NS, "Hit").item(0).getTextContent());
        assertEquals(0, root.getElementsByTagName("em").getLength());
    }

    @Test
    void removesLaterMarkersButPreservesTheirText() throws Exception {
        Element root = serialize("<Hit>A<Hit>B</Hit>C</Hit><Hit>D</Hit>");

        assertEquals("ABCD", root.getTextContent());
        assertEquals(1, root.getElementsByTagNameNS(DzpConstants.FCS_HITS_NS, "Hit").getLength());
        assertEquals("AB", root.getElementsByTagNameNS(DzpConstants.FCS_HITS_NS, "Hit").item(0).getTextContent());
    }

    @Test
    void doesNotRescanTextForNewOpeningMarkersButRemovesNewClosingMarkersInTheSecondPass() throws Exception {
        Element root = serialize("<Hit>X</Hit><Hi<Hit>t>|</Hi<Hit>t>|</Hi</Hit>t>");

        assertEquals("X<Hit>||</Hit>", root.getTextContent());
        assertEquals(1, root.getElementsByTagNameNS(DzpConstants.FCS_HITS_NS, "Hit").getLength());
    }

    @ParameterizedTest
    @ValueSource(strings = {"<Hi\u0000t>X</Hit>", "<Hi\uD800t>X</Hit>", "<Hi\uDC00t>X</Hit>",
        "<Hi\uFFFEt>X</Hit>", "<Hi\uFFFFt>X</Hit>"})
    void findsMarkersAfterDiscardedCharactersHaveBeenRemoved(String snippet) throws Exception {
        Element root = serialize(snippet);

        assertEquals("X", root.getTextContent());
        assertEquals(1, root.getElementsByTagNameNS(DzpConstants.FCS_HITS_NS, "Hit").getLength());
    }

    @Test
    void keepsSupplementaryAndC1CharactersButDoesNotJoinPreviouslyIsolatedSurrogates() throws Exception {
        String text = "<Hit>A\u0000\uD800B\uDC00\uFFFE\uFFFF\uD83D\uDE00\uD83D\u0000\uDE00\u007F\u0085\u009F\u2028</Hit>";

        assertEquals("AB\uD83D\uDE00\u007F\u0085\u009F\u2028", serialize(text).getTextContent());
    }

    @Test
    void normalizesLineEndingsAfterRemovingExtraMarkersButNotAcrossRetainedTags() throws Exception {
        assertEquals("A\nX\nB", serialize("A\r<Hit>X</Hit>\nB").getTextContent());
        assertEquals("XA\nB", serialize("<Hit>X</Hit>A\r<Hit>\nB</Hit>").getTextContent());
        assertEquals("XA\nB", serialize("<Hit>X</Hit>A\r\u0000\nB").getTextContent());
        assertEquals("XA\nB\nC\nD", serialize("<Hit>X</Hit>A\r\nB\rC\nD").getTextContent());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "plain text", " \t\n ", "<hit>lowercase</hit>", "<Hit />", "&lt;Hit&gt;"})
    void rejectsEmptyOrMarkerlessTextRatherThanInventingAHit(String snippet) {
        assertThrows(XMLStreamException.class, () -> serialize(snippet));
    }

    @ParameterizedTest
    @ValueSource(strings = {"<Hit></Hit>", "<Hit>\u0000\uD800\uFFFE\uDC00\uFFFF</Hit>",
        "<Hit>\uD800\u0000\uDC00</Hit>", "<Hit><Hit></Hit>text</Hit>",
        "<Hit></Hi<Hit>t></Hit>", "<Hit></Hit><Hit>later real hit</Hit>", "<Hit> \t\r\n </Hit>",
        "<Hit>\u2003</Hit>"})
    void rejectsFirstHitsWhichAreEmptyAfterXmlAndMarkerNormalization(String snippet) {
        assertThrows(XMLStreamException.class, () -> serialize(snippet));
    }

    @Test
    void rejectsBeforeWritingAnySnippetContent() throws Exception {
        StringWriter output = new StringWriter();
        XMLStreamWriter writer = XMLOutputFactory.newFactory().createXMLStreamWriter(output);
        try {
            writer.writeStartElement("root");
            writer.flush();
            String before = output.toString();
            assertThrows(XMLStreamException.class, () -> SnippetWriter.write(writer, "context <Hit></Hit>"));
            writer.flush();
            assertEquals(before, output.toString());
            writer.writeEndElement();
        } finally {
            writer.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"<Hit>x", "x</Hit>", "</Hit><Hit>x", "<Hit x=\"a\">x</Hit>"})
    void rejectsUnpairedOrReversedFirstMarkers(String snippet) {
        assertThrows(XMLStreamException.class, () -> serialize(snippet));
    }

    @ParameterizedTest
    @MethodSource("xml10ForbiddenControls")
    void rejectsControlsEvenIfTheStreamWriterWouldAcceptThem(String snippet) {
        assertThrows(XMLStreamException.class, () -> serialize(snippet));
    }

    static Stream<String> xml10ForbiddenControls() {
        return IntStream.range(1, 0x20).filter(value -> value != '\t' && value != '\n' && value != '\r')
                .mapToObj(value -> "before <Hit>" + (char) value + "</Hit> after");
    }

    private static Element serialize(String snippet) throws Exception {
        StringWriter output = new StringWriter();
        XMLStreamWriter writer = XMLOutputFactory.newFactory().createXMLStreamWriter(output);
        try {
            writer.writeStartDocument();
            writer.setPrefix(DzpConstants.FCS_HITS_PREFIX, DzpConstants.FCS_HITS_NS);
            writer.writeStartElement("root");
            writer.writeNamespace(DzpConstants.FCS_HITS_PREFIX, DzpConstants.FCS_HITS_NS);
            SnippetWriter.write(writer, snippet);
            writer.writeEndElement();
            writer.writeEndDocument();
        } finally {
            writer.close();
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(output.toString()))).getDocumentElement();
    }
}
