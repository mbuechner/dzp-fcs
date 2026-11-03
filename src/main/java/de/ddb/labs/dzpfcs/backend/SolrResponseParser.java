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
package de.ddb.labs.dzpfcs.backend;

import de.ddb.labs.dzpfcs.logging.RequestTelemetry;
import de.ddb.labs.dzpfcs.logging.StructuredLog;
import de.ddb.labs.dzpfcs.model.Results;
import de.ddb.labs.dzpfcs.model.ResultsEntry;
import eu.clarin.sru.server.SRUConstants;
import eu.clarin.sru.server.SRUException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

/**
 * Decodes one complete Solr response without changing process-wide JSON settings.
 *
 * <p>The immutable reader is shared safely; JSON trees, entries and snippet lists
 * belong to one call. Only fields used by the endpoint are mapped. This avoids
 * a second conversion of generic maps into DTOs and repeated JSON-path lookups.
 * HTTP execution and retries have already finished when this parser is called.</p>
 */
public final class SolrResponseParser {

    private static final Logger LOGGER = LogManager.getLogger(SolrResponseParser.class);
    private static final ObjectReader JSON = JsonMapper.builder().build().reader();

    private SolrResponseParser() {
    }

    /**
     * Preserve the original SRU offset and diagnose its range before reading page
     * documents. Malformed required fields become a system diagnostic, never a
     * retry. Unknown response fields and an absent timing header are harmless.
     */
    public static Results parse(String json, String pid, String query, int offset) throws SRUException {
        try {
            JsonNode root = JSON.readTree(json);
            JsonNode count = root.path("response").path("numFound");
            require(count.isNumber() && count.canConvertToExactIntegral() && count.canConvertToInt(),
                    "Solr response.numFound must be an integer in the SRU count range.");
            int total = count.intValue();
            require(total >= 0, "Solr response.numFound must not be negative.");
            recordTiming(root.path("responseHeader"));
            if (offset > total) {
                throw new SRUException(SRUConstants.SRU_FIRST_RECORD_POSITION_OUT_OF_RANGE);
            }
            return new Results(pid, query, readRecords(root), total, offset);
        } catch (RuntimeException e) {
            throw new SRUException(SRUConstants.SRU_GENERAL_SYSTEM_ERROR,
                    "Invalid DDB-API search response.", e);
        }
    }

    /** Map backend order and raw snippets once; missing highlights leave records present. */
    static List<ResultsEntry> readRecords(JsonNode root) {
        JsonNode documents = root.path("response").path("docs");
        require(documents.isArray(), "Solr response.docs must be an array.");
        JsonNode highlighting = root.path("highlighting");
        validateHighlights(highlighting);
        List<ResultsEntry> entries = new ArrayList<>(documents.size());
        for (JsonNode document : documents) {
            JsonNode id = document.path("id");
            String identifier = id.isString() ? id.asString() : null;
            require(identifier != null && !identifier.isBlank() && identifier.length() >= 32,
                    "Solr documents must contain an id with at least 32 characters.");
            JsonNode page = document.path("pagenumber");
            require(absent(page) || page.isString() || page.isNumber(),
                    "Solr pagenumber must be a string or number when present.");
            ResultsEntry entry = new ResultsEntry();
            entry.setId(identifier);
            entry.setPagenumber(absent(page) ? null : page.asString());
            JsonNode snippets = highlighting.path(entry.getId()).path("plainpagefulltext");
            if (!absent(snippets)) {
                List<String> text = new ArrayList<>(snippets.size());
                for (JsonNode snippet : snippets) {
                    text.add(snippet.asString());
                }
                entry.setPlainpagefulltext(text);
            }
            entries.add(entry);
        }
        return entries;
    }

    /** Validate supplied highlight types, including unmatched entries, without remapping them. */
    private static void validateHighlights(JsonNode highlighting) {
        if (absent(highlighting)) {
            return;
        }
        require(highlighting.isObject(), "Solr highlighting must be an object.");
        for (JsonNode fields : highlighting) {
            if (fields.isNull()) {
                continue;
            }
            require(fields.isObject(), "Solr document highlights must be an object.");
            JsonNode snippets = fields.path("plainpagefulltext");
            if (!absent(snippets)) {
                require(snippets.isArray(), "Solr snippets must be an array of strings.");
                for (JsonNode snippet : snippets) {
                    require(snippet.isString(), "Solr snippets must be an array of strings.");
                }
            }
        }
    }

    /** Optional metadata is observed without making its presence a search prerequisite. */
    private static void recordTiming(JsonNode header) {
        if (!header.isObject()) {
            return;
        }
        JsonNode time = header.path("QTime");
        Long millis = time.isNumber() && time.canConvertToExactIntegral() && time.canConvertToLong()
                && time.longValue() >= 0 ? time.longValue() : null;
        JsonNode partial = header.path("partialResults");
        Boolean partialResults = absent(partial) ? null : partial.isBoolean() ? partial.booleanValue()
                : partial.isString() && ("true".equalsIgnoreCase(partial.asString())
                        || "omitted".equalsIgnoreCase(partial.asString()));
        RequestTelemetry.recordSolrTiming(millis, partialResults);
        if (Boolean.TRUE.equals(partialResults)) {
            StructuredLog.event(LOGGER, Level.WARN, "solr.partial_results", Map.of("partialResults", true));
        }
    }

    private static boolean absent(JsonNode node) {
        return node.isMissingNode() || node.isNull();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
