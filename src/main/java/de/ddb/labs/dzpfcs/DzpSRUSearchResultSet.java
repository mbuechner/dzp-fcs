/*
 * Copyright 2023 Michael Büchner, Deutsche Digitale Bibliothek
 *
 * This program is free software: you can redistribute it and/or modify 
 * it under the terms of the GNU General Public License as published by 
 * the Free Software Foundation, either version 3 of the License, or 
 * (at your option) any later version. 
 *  
 * This program is distributed in the hope that it will be useful, 
 * but WITHOUT ANY WARRANTY; without even the implied warranty of 
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the 
 * GNU General Public License for more details. 
 * 
 * You should have received a copy of the GNU General Public License 
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package de.ddb.labs.dzpfcs;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import de.ddb.labs.dzpfcs.searcher.Results;
import eu.clarin.sru.server.SRUConstants;
import eu.clarin.sru.server.SRUDiagnostic;
import eu.clarin.sru.server.SRUDiagnosticList;
import eu.clarin.sru.server.SRUException;
import eu.clarin.sru.server.SRURequest;
import eu.clarin.sru.server.SRUSearchResultSet;
import eu.clarin.sru.server.SRUServerConfig;
import eu.clarin.sru.server.fcs.XMLStreamWriterHelper;
import de.ddb.labs.dzpfcs.searcher.ResultsEntry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import org.xml.sax.Attributes;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * A result set of a <em>searchRetrieve</em> operation. It it used to iterate
 * over the result set and provides a method to serialize the record in the
 * requested format.
 * <p>
 * A <code>SRUSearchResultSet</code> object maintains a cursor pointing to its
 * current record. Initially the cursor is positioned before the first record.
 * The <code>next</code> method moves the cursor to the next record, and because
 * it returns <code>false</code> when there are no more records in the
 * <code>SRUSearchResultSet</code> object, it can be used in a
 * <code>while</code> loop to iterate through the result set.
 * </p>
 * <p>
 * A required implemention for the target search engine.
 * </p>
 * <p>
 * This class only implements the minimal set of methods required to be a valid
 * implementation and to run.
 * </p>
 *
 * @see SRUSearchResultSet
 * @see <a href="http://www.loc.gov/standards/sru/specs/search-retrieve.html">
 * SRU Search Retrieve Operation</a>
 */
public class DzpSRUSearchResultSet extends SRUSearchResultSet {

    private static final Logger LOGGER = LogManager.getLogger(DzpSRUSearchResultSet.class);

    /**
     * SRU Server Config, might be useful for response generation?
     */
    SRUServerConfig serverConfig = null;

    /**
     * The SRU Request we generate our response for. Can be used to access
     * requested parameters.
     */
    SRURequest request = null;

    /**
     * The list of Data View identifiers we need to generate our response for.
     */
    private final Set<String> extraDataviews;

    /**
     * Results wrapper container for easy access to metadata (total count etc.)
     */
    private Results results; // FIXME: change to correct result object

    /**
     * The record cursor position for iterating through the result set.
     */
    private int currentRecordCursor = 0;

    protected static final SAXParserFactory factory;

    static {
        factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setValidating(false);
        factory.setXIncludeAware(false);
    }

    /**
     * Constructor.
     *
     * @param serverConfig the {@link SRUServerConfig} object for this search
     * engine
     * @param request the {@link SRURequest} with request parameters
     * @param diagnostics the {@link SRUDiagnosticList} object for storing
     * non-fatal diagnostics
     * @param dataviews a list of String Data View identifiers to generate
     * responses for. May be empty but must not be <code>null</code>.
     * @param results the actual results from the search engine
     */
    protected DzpSRUSearchResultSet(SRUServerConfig serverConfig, SRURequest request, SRUDiagnosticList diagnostics, List<String> dataviews, Results results) {
        super(diagnostics);
        this.serverConfig = serverConfig;
        this.request = request;

        this.results = results;
        currentRecordCursor = -1;

        extraDataviews = new HashSet<>(dataviews);
    }

    /**
     * An identifier for the current record by which it can unambiguously be
     * retrieved in a subsequent operation.
     *
     * @return identifier for the record or <code>null</code> of none is
     * available
     * @throws NoSuchElementException result set is past all records
     */
    @Override
    public String getRecordIdentifier() {
        return null;
    }

    /**
     * Serialize the current record in the requested format.
     *
     * @return
     * @see #getRecordSchemaIdentifier()
     */
    @Override
    public String getRecordSchemaIdentifier() {
        return request.getRecordSchemaIdentifier() != null ? request.getRecordSchemaIdentifier() : DzpConstants.CLARIN_FCS_RECORD_SCHEMA;
    }

    /**
     * Get surrogate diagnostic for current record. If this method returns a
     * diagnostic, the writeRecord method will not be called. The default
     * implementation returns <code>null</code>.
     *
     * @return a surrogate diagnostic or <code>null</code>
     * @see
     * <a href="https://github.com/clarin-eric/fcs-korp-endpoint/blob/ffccf7f65cc55744e1b1a8cebacce5485c530bda/src/main/java/se/gu/spraakbanken/fcs/endpoint/korp/KorpSRUSearchResultSet.java#L242-L253">
     * Reference Implementation, Korp Endpoint</a>
     */
    @Override
    public SRUDiagnostic getSurrogateDiagnostic() {
        if ((getRecordSchemaIdentifier() != null)
                && !DzpConstants.CLARIN_FCS_RECORD_SCHEMA.equals(getRecordSchemaIdentifier())) {
            return new SRUDiagnostic(SRUConstants.SRU_RECORD_NOT_AVAILABLE_IN_THIS_SCHEMA, getRecordSchemaIdentifier(), "Record is not available in record schema \"" + getRecordSchemaIdentifier() + "\".");
        }

        return null;
    }

    /**
     * The number of records matched by the query. If the query fails this must
     * be 0. If the search engine cannot determine the total number of matched
     * by a query, it must return -1.
     *
     * @return the total number of results or 0 if the query failed or -1 if the
     * search engine cannot determine the total number of results
     */
    @Override
    public int getTotalRecordCount() {
        return (int) results.getTotal();
    }

    /**
     * The number of records matched by the query but at most as the number of
     * records requested to be returned (maximumRecords parameter). If the query
     * fails this must be 0.
     *
     * @return the number of results or 0 if the query failed
     */
    @Override
    public int getRecordCount() {
        return results.getResults().size();
    }

    /**
     * Moves the cursor forward one record from its current position. A
     * <code>SRUSearchResultSet</code> cursor is initially positioned before the
     * first record; the first call to the method <code>next</code> makes the
     * first record the current record; the second call makes the second record
     * the current record, and so on.
     * <p>
     * When a call to the <code>next</code> method returns <code>false</code>,
     * the cursor is positioned after the last record.
     * </p>
     *
     * @return <code>true</code> if the new current record is valid;
     * <code>false</code> if there are no more records
     * @throws SRUException if an error occurred while fetching the next record
     */
    @Override
    public boolean nextRecord() throws SRUException {
        if (currentRecordCursor < (getRecordCount() - 1)) {
            currentRecordCursor++;
            return true;
        }
        return false;
    }

    @Override
    public void writeRecord(XMLStreamWriter writer) throws XMLStreamException {
        ResultsEntry result = results.getResults().get(currentRecordCursor);

        XMLStreamWriterHelper.writeStartResource(writer, results.getPid(), null);
        XMLStreamWriterHelper.writeStartResourceFragment(writer, result.getId(), result.getDzpUrl(results.getQuery()));

        if (request != null && request.isQueryType(DzpConstants.SRU_QUERY_TYPE_LEX)) {
            writeLexHitsDataview(writer, result);
        } else {
            writeHitsDataview(writer, result);
        }

        XMLStreamWriterHelper.writeEndResourceFragment(writer);
        XMLStreamWriterHelper.writeEndResource(writer);
    }

    protected void writeHitsDataview(XMLStreamWriter writer, ResultsEntry result) throws XMLStreamException {
        XMLStreamWriterHelper.writeStartDataView(writer, DzpConstants.FCS_HITS_MIMETYPE);
        writer.setPrefix(DzpConstants.FCS_HITS_PREFIX, DzpConstants.FCS_HITS_NS);
        writer.writeStartElement(DzpConstants.FCS_HITS_NS, "Result");
        writer.writeNamespace(DzpConstants.FCS_HITS_PREFIX, DzpConstants.FCS_HITS_NS);

        writeSolrHitsDataviewBytedXMLDoc(writer, result.getPlainpagefulltext().get(0));

        writer.writeEndElement(); // "Result" element
        XMLStreamWriterHelper.writeEndDataView(writer);
    }

    /**
     * Source
     * https://gist.github.com/Querela/825a084f94b30de88827050eddc8e361#file-sawsrusearchresultset-java-L137-L262
     *
     * @param writer
     * @param result
     * @throws XMLStreamException
     */
    protected void writeLexHitsDataview(XMLStreamWriter writer, ResultsEntry result) throws XMLStreamException {
        XMLStreamWriterHelper.writeStartDataView(writer, DzpConstants.FCS_HITS_MIMETYPE);
        writer.setPrefix(DzpConstants.FCS_HITS_PREFIX, DzpConstants.FCS_HITS_NS);
        writer.writeStartElement(DzpConstants.FCS_HITS_NS, "Result");
        writer.writeNamespace(DzpConstants.FCS_HITS_PREFIX, DzpConstants.FCS_HITS_NS);

        writeSolrHitsDataviewBytedXMLDoc(writer, result.getPlainpagefulltext().get(0));

        writer.writeEndElement(); // "Result" element
        XMLStreamWriterHelper.writeEndDataView(writer);
    }

    /**
     * Helper method for
     * {@link #writeLexHitsDataview(XMLStreamWriter, ResultEntry)} and
     * {@link #writeHitsDataview(XMLStreamWriter, ResultEntry)} to write an XML
     * string to output. Also adds the <code>hits:</code> prefixes.
     *
     * @param writer
     * @param text
     * @throws XMLStreamException
     */
    protected static void writeSolrHitsDataviewBytedXMLDoc(XMLStreamWriter writer, String text)
            throws XMLStreamException {
        final String marker = "writeSolrHitsDataviewBytedXMLDoc";

        try {

            final InputStream is = new ByteArrayInputStream(("<" + marker + ">" + text + "</" + marker + ">").getBytes(StandardCharsets.UTF_8));

            final SAXParser parser = factory.newSAXParser();

            parser.parse(is, new DefaultHandler() {
                public boolean isBlank(final String s) {
                    // from: org.apache.logging.log4j.util.Strings.isBlank()
                    if (s == null || s.isEmpty()) {
                        return true;
                    }
                    for (int i = 0; i < s.length(); i++) {
                        char c = s.charAt(i);
                        if (!Character.isWhitespace(c)) {
                            return false;
                        }
                    }
                    return true;
                }

                @Override
                public void characters(char[] ch, int start, int length) throws SAXException {
                    // LOGGER.info("characters: {}", Arrays.copyOfRange(ch, start, start + length));
                    // strip blanks
                    // TODO: maybe with indent == 0, just check for single line-breaks after element ends?
                    if (isBlank(new String(ch, start, length))) {
                        return;
                    }

                    try {
                        writer.writeCharacters(ch, start, length);
                    } catch (XMLStreamException e) {
                        throw new SAXException(e);
                    }
                }

                @Override
                public void endElement(String uri, String localName, String qName) throws SAXException {
                    if (qName.equals(marker)) {
                        return;
                    }
                    try {
                        writer.writeEndElement();
                    } catch (XMLStreamException e) {
                        throw new SAXException(e);
                    }
                }

                private Map<String, String> prefixes = new HashMap<>();

                @Override
                public void startPrefixMapping(String prefix, String uri) throws SAXException {
                    super.startPrefixMapping(prefix, uri);
                    // writer.writeNamespace(prefix, uri);
                    prefixes.put(prefix, uri);
                }

                @Override
                public void startElement(String uri, String localName, String qName, Attributes attributes)
                        throws SAXException {
                    if (qName.equals(marker)) {
                        return;
                    }
                    try {
                        if (qName.equals("Hit")) {
                            writer.writeStartElement(DzpConstants.FCS_HITS_NS, qName);
                        } else {
                            writer.writeStartElement(qName);
                            // writer.writeStartElement(qName, localName, uri);
                        }
                        if (!prefixes.isEmpty()) {
                            for (Map.Entry<String, String> entry : prefixes.entrySet()) {
                                writer.writeNamespace(entry.getKey(), entry.getValue());
                            }
                            prefixes.clear();
                        }

                        for (int i = 0; i < attributes.getLength(); i++) {
                            writer.writeAttribute(attributes.getQName(i), attributes.getValue(i));
                        }
                    } catch (XMLStreamException e) {
                        throw new SAXException(e);
                    }
                }
            });
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new XMLStreamException(e);
        }
    }
}
