/*
 * Copyright 2023-2026 Michael Büchner, Deutsche Digitale Bibliothek
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
package de.ddb.labs.dzpfcs.endpoint;

import de.ddb.labs.dzpfcs.model.Results;
import de.ddb.labs.dzpfcs.model.ResultsEntry;
import eu.clarin.sru.server.SRUConstants;
import eu.clarin.sru.server.SRUDiagnostic;
import eu.clarin.sru.server.SRUDiagnosticList;
import eu.clarin.sru.server.SRUException;
import eu.clarin.sru.server.SRURequest;
import eu.clarin.sru.server.SRUSearchResultSet;
import eu.clarin.sru.server.fcs.XMLStreamWriterHelper;
import java.util.NoSuchElementException;
import java.util.Objects;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

/**
 * Iterates one backend page and serializes its mandatory FCS HITS representation.
 *
 * <p>Each instance belongs to one synchronous SRU request. Its cursor and prepared
 * record are never shared between requests. The page has a stable list structure;
 * its mutable backend DTOs are read only while preparing the current record.
 * Preparation snapshots the ID, link and first snippet before any payload XML is
 * written, so a bad record can become a surrogate without corrupting other hits.</p>
 *
 * <p>Only Basic Search/HITS is implemented. The first snippet and its first
 * nonblank Hit are retained; later snippets never replace an unusable first one.
 * Surrogates occupy their original record positions and leave counts unchanged.</p>
 */
public final class DzpSRUSearchResultSet extends SRUSearchResultSet {

    private final String recordSchema;
    private final Results results;
    private int currentRecordCursor = -1;
    private PreparedRecord preparedRecord;

    /**
     * @param request request whose normalized schema identifier is used; null selects FCS
     * @param diagnostics request-scoped non-fatal diagnostic sink
     * @param results stable backend page with its original total and zero-based offset
     */
    protected DzpSRUSearchResultSet(SRURequest request, SRUDiagnosticList diagnostics, Results results) {
        super(diagnostics);
        String requestedSchema = request == null ? null : request.getRecordSchemaIdentifier();
        recordSchema = requestedSchema == null ? DzpConstants.CLARIN_FCS_RECORD_SCHEMA : requestedSchema;
        this.results = Objects.requireNonNull(results, "results");
    }

    /** No SRU identifier lookup is advertised for the individual backend records. */
    @Override
    public String getRecordIdentifier() {
        return null;
    }

    /** The framework resolves supported short schema names before constructing this result set. */
    @Override
    public String getRecordSchemaIdentifier() {
        return recordSchema;
    }

    /**
     * Return diagnostic 67 when this record cannot be represented in the requested
     * schema. The framework then serializes the diagnostic instead of calling
     * writeRecord; no placeholder text or invented Hit is sent.
     */
    @Override
    public SRUDiagnostic getSurrogateDiagnostic() {
        if (!DzpConstants.CLARIN_FCS_RECORD_SCHEMA.equals(recordSchema)) {
            return unavailable("Record is not available in the requested schema.");
        }
        return currentRecord().diagnostic();
    }

    /** The parser validates that the backend total fits the SRU framework's integer range. */
    @Override
    public int getTotalRecordCount() {
        return (int) results.getTotal();
    }

    /** Includes surrogate records, preserving the original backend page size. */
    @Override
    public int getRecordCount() {
        return results.getResults().size();
    }

    /** Move forward exactly once per backend record, including unavailable records. */
    @Override
    public boolean nextRecord() throws SRUException {
        preparedRecord = null;
        if (currentRecordCursor < getRecordCount() - 1) {
            currentRecordCursor++;
            return true;
        }
        currentRecordCursor = getRecordCount();
        return false;
    }

    @Override
    public void writeRecord(XMLStreamWriter writer) throws XMLStreamException {
        PreparedRecord record = currentRecord();
        if (!DzpConstants.CLARIN_FCS_RECORD_SCHEMA.equals(recordSchema) || record.diagnostic() != null) {
            // Guard direct callers too, before writing even the first Resource element.
            throw new XMLStreamException("Record requires an SRU surrogate diagnostic.");
        }

        XMLStreamWriterHelper.writeStartResource(writer, results.getPid(), null);
        XMLStreamWriterHelper.writeStartResourceFragment(writer, record.id(), record.reference());
        writeHitsDataView(writer, record.snippet());
        XMLStreamWriterHelper.writeEndResourceFragment(writer);
        XMLStreamWriterHelper.writeEndResource(writer);
    }

    /** Prepare once per cursor; missing snippets and invalid IDs do not remove a result position. */
    private PreparedRecord currentRecord() {
        if (currentRecordCursor < 0 || currentRecordCursor >= getRecordCount()) {
            throw new NoSuchElementException("Result cursor is not positioned on a record.");
        }
        if (preparedRecord == null) {
            ResultsEntry entry = results.getResults().get(currentRecordCursor);
            try {
                // URL/ID validation precedes all output, not just the snippet check.
                String reference = entry.getDzpUrl(results.getQuery());
                if (entry.getPlainpagefulltext().isEmpty()) {
                    preparedRecord = new PreparedRecord(null, null, null,
                            unavailable("Record has no highlighted snippet for the mandatory FCS HITS view."));
                } else {
                    preparedRecord = new PreparedRecord(entry.getId(), reference,
                            SnippetWriter.prepare(entry.getPlainpagefulltext().get(0)), null);
                }
            } catch (XMLStreamException | IllegalArgumentException invalidRecord) {
                preparedRecord = new PreparedRecord(null, null, null,
                        unavailable("Record has an invalid identifier or highlighted snippet for the FCS HITS view."));
            }
        }
        return preparedRecord;
    }

    private SRUDiagnostic unavailable(String message) {
        return new SRUDiagnostic(SRUConstants.SRU_RECORD_NOT_AVAILABLE_IN_THIS_SCHEMA, recordSchema, message);
    }

    private static void writeHitsDataView(XMLStreamWriter writer, SnippetWriter.PreparedSnippet snippet)
            throws XMLStreamException {
        XMLStreamWriterHelper.writeStartDataView(writer, DzpConstants.FCS_HITS_MIMETYPE);
        writer.setPrefix(DzpConstants.FCS_HITS_PREFIX, DzpConstants.FCS_HITS_NS);
        writer.writeStartElement(DzpConstants.FCS_HITS_NS, "Result");
        writer.writeNamespace(DzpConstants.FCS_HITS_PREFIX, DzpConstants.FCS_HITS_NS);
        snippet.write(writer);
        writer.writeEndElement();
        XMLStreamWriterHelper.writeEndDataView(writer);
    }

    /** All fields belong to the same cursor position and remain unchanged during serialization. */
    private record PreparedRecord(String id, String reference, SnippetWriter.PreparedSnippet snippet,
            SRUDiagnostic diagnostic) {
    }
}
