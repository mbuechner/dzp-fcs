package de.ddb.labs.dzpfcs.endpoint;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import org.apache.commons.text.StringEscapeUtils;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Compare conforming HITS output against the former escaping/regex/SAX pipeline.
 * The independent legacy oracle deliberately lives only in tests: replacement
 * order and XML parser normalization have subtle, externally visible effects.
 * Legacy output without a nonempty first Hit is deliberately rejected now so
 * the record layer can return a surrogate instead of a nonconforming payload.
 */
class SnippetCompatibilityTest {

    @Test
    void preservesLegacyOutputForTextMarkersEntitiesAndUnicodeBoundaries() throws Exception {
        List<String> examples = List.of(
                "", "plain", " \t\n ", "\r\n\r", "<Hit></Hit>",
                "<Hit>A</Hit> and <Hit>B</Hit>", "<Hit>A<Hit>B</Hit>C</Hit>",
                "&amp; &#65; &lt;Hit&gt; &not-an-entity;",
                "<em>literal</em><Hit>A & B ]]> \" '</Hit>",
                "<Hit>X</Hit><Hi<Hit>t>|</Hi<Hit>t>|</Hi</Hit>t>",
                "A\r<Hit></Hit>\nB", "<Hit>X</Hit>A\r<Hit>\nB</Hit>",
                "A\r\u0000\nB", "<Hi\u0000t>A</Hit>", "<Hi\uD800t>A</Hit>",
                "<Hi\uFFFEt>A</Hit>", "A\u0000\uD800B\uDC00\uFFFE\uFFFF\uD83D\uDE80",
                "\uD83D\u0000\uDE80", "\u007F\u0085\u009F\u2028",
                "<Hit>\r\n\uD83D\uDE80\r</Hit>",
                "x".repeat(16383) + "\uD83D\uDE80<Hit>Berlin</Hit>" + " & ".repeat(200));
        for (String snippet : examples) {
            compare(snippet);
        }
    }

    @Test
    void rejectsTheSameMalformedMarkerAndControlInputs() throws Exception {
        for (String snippet : List.of("<Hit>x", "x</Hit>", "</Hit><Hit>",
                "<Hit x=\"a\">B</Hit>", "<Hit>A\u0001</Hit>", "plain\u000C")) {
            assertThrows(XMLStreamException.class, () -> render(snippet, true));
            assertThrows(XMLStreamException.class, () -> render(snippet, false));
        }
    }

    @Test
    void generatedMarkerBoundariesMatchTheLegacyPipeline() throws Exception {
        Random random = new Random(42817);
        String[] tokens = {"a", "&amp;", "&#1;", "<", ">", "<Hit>", "</Hit>",
            "<Hi", "</Hi", "t>", "\r", "\n", "\t", "\u0000", "\u0001",
            "\uD83D", "\uDE80", "\uFFFE", "München"};
        for (int sample = 0; sample < 300; sample++) {
            StringBuilder input = new StringBuilder();
            if (random.nextBoolean()) {
                input.append("<Hit>first</Hit>");
            }
            int parts = 1 + random.nextInt(10);
            for (int part = 0; part < parts; part++) {
                input.append(tokens[random.nextInt(tokens.length)]);
            }
            compare(input.toString());
        }
    }

    private static void compare(String snippet) throws Exception {
        Element expected;
        try {
            expected = render(snippet, true);
        } catch (XMLStreamException invalidLegacyInput) {
            assertThrows(XMLStreamException.class, () -> render(snippet, false));
            return;
        }
        var hits = expected.getElementsByTagNameNS(DzpConstants.FCS_HITS_NS, "Hit");
        if (hits.getLength() == 0 || hits.item(0).getTextContent().isBlank()) {
            assertThrows(XMLStreamException.class, () -> render(snippet, false));
            return;
        }
        Element actual = render(snippet, false);
        assertTrue(expected.isEqualNode(actual), () -> "Snippet output differs: " + printable(snippet));
    }

    private static String printable(String snippet) {
        return snippet.replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
    }

    private static Element render(String snippet, boolean legacy) throws Exception {
        StringWriter output = new StringWriter();
        XMLStreamWriter writer = XMLOutputFactory.newFactory().createXMLStreamWriter(output);
        try {
            writer.writeStartDocument();
            writer.setPrefix(DzpConstants.FCS_HITS_PREFIX, DzpConstants.FCS_HITS_NS);
            writer.writeStartElement("root");
            writer.writeNamespace(DzpConstants.FCS_HITS_PREFIX, DzpConstants.FCS_HITS_NS);
            if (legacy) {
                legacyWrite(writer, snippet);
            } else {
                SnippetWriter.write(writer, snippet);
            }
            writer.writeEndElement();
            writer.writeEndDocument();
        } finally {
            writer.close();
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(output.toString()))).getDocumentElement();
    }

    /** Exact former transformations, kept independently of the new implementation. */
    private static void legacyWrite(XMLStreamWriter writer, String snippet) throws XMLStreamException {
        String text = StringEscapeUtils.escapeXml11(snippet)
                .replaceFirst("&lt;Hit&gt;", "<Hit>")
                .replaceFirst("&lt;/Hit&gt;", "</Hit>")
                .replaceAll("&lt;Hit&gt;", "")
                .replaceAll("&lt;/Hit&gt;", "");
        String marker = "writeSolrHitsDataviewBytedXMLDoc";
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setValidating(false);
        factory.setXIncludeAware(false);
        try {
            byte[] bytes = ("<" + marker + ">" + text + "</" + marker + ">").getBytes(StandardCharsets.UTF_8);
            factory.newSAXParser().parse(new ByteArrayInputStream(bytes), new DefaultHandler() {
                @Override
                public void characters(char[] characters, int start, int length) throws SAXException {
                    try {
                        writer.writeCharacters(characters, start, length);
                    } catch (XMLStreamException e) {
                        throw new SAXException(e);
                    }
                }

                @Override
                public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
                    if (!marker.equals(qName)) {
                        try {
                            writer.writeStartElement(DzpConstants.FCS_HITS_NS, qName);
                        } catch (XMLStreamException e) {
                            throw new SAXException(e);
                        }
                    }
                }

                @Override
                public void endElement(String uri, String localName, String qName) throws SAXException {
                    if (!marker.equals(qName)) {
                        try {
                            writer.writeEndElement();
                        } catch (XMLStreamException e) {
                            throw new SAXException(e);
                        }
                    }
                }
            });
        } catch (javax.xml.parsers.ParserConfigurationException | SAXException | java.io.IOException e) {
            throw new XMLStreamException(e);
        }
    }
}
