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
package de.ddb.labs.dzpfcs.logging;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ObjectMessage;

/**
 * Emits application events without converting metric values or user input into
 * a hand-built JSON string. The configured JSON Template Layout serializes the
 * {@link ObjectMessage} payload as an object, preserving numbers and booleans
 * and escaping quotes, line breaks and other JSON control characters.
 *
 * <p>The logging envelope supplies time, severity, logger, thread and the
 * current {@code requestId} from Log4j's thread context. Callers supply only
 * event-specific fields; ordinary framework messages remain JSON strings.</p>
 */
public final class StructuredLog {

    // Keep each chunk below the layout's per-string limit (log4j2.xml).
    // Ordinary queries need no extra copies. Exceptional long POST queries
    // retain their complete text in individually bounded chunks, while the
    // layout renders the original query value as a shortened preview.
    private static final int QUERY_CHUNK_SIZE = 32_768;
    private static final int QUERY_PREVIEW_LIMIT = 65_536;

    private StructuredLog() {
    }

    /**
     * Log an event with typed, event-specific attributes.
     *
     * @param logger destination logger
     * @param level event severity
     * @param event non-blank event name, stored in {@code message.event}
     * @param fields attributes whose types must be retained; null values and
     * null keys are omitted, and the reserved {@code event} key is ignored
     */
    public static void event(Logger logger, Level level, String event, Map<String, ?> fields) {
        event(logger, level, event, fields, null);
    }

    /**
     * Log an event and an optional exception. The throwable is attached to the
     * log event, allowing the layout to render its type, message and escaped
     * stack trace separately from the application payload.
     *
     * <p>A shallow, immutable snapshot prevents later changes to the caller's
     * map from changing the event. Nested collections and other mutable values
     * must remain unchanged after this call, including with asynchronous logging.
     * Disabled levels do not allocate a payload snapshot.</p>
     *
     * @param logger destination logger
     * @param level event severity
     * @param event non-blank event name, stored in {@code message.event}
     * @param fields typed attributes; null entries and the reserved
     * {@code event} key are omitted
     * @param error optional exception, or {@code null} for an ordinary event
     * @throws NullPointerException if a required argument is null
     * @throws IllegalArgumentException if the event name is blank
     */
    public static void event(Logger logger, Level level, String event, Map<String, ?> fields, Throwable error) {
        Objects.requireNonNull(logger, "logger");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(fields, "fields");
        if (event.isBlank()) {
            throw new IllegalArgumentException("event must not be blank");
        }
        if (!logger.isEnabled(level)) {
            return;
        }

        final Map<String, Object> payload = LinkedHashMap.newLinkedHashMap(fields.size() + 1);
        payload.put("event", event);
        fields.forEach((key, value) -> {
            if (key != null && value != null && !"event".equals(key)) {
                payload.put(key, value);
            }
        });
        if (payload.get("query") instanceof String query && query.length() > QUERY_PREVIEW_LIMIT) {
            List<String> chunks = new ArrayList<>();
            for (int start = 0; start < query.length();) {
                int end = start + Math.min(QUERY_CHUNK_SIZE, query.length() - start);
                // Keep supplementary Unicode characters intact in each JSON string.
                if (end < query.length() && Character.isHighSurrogate(query.charAt(end - 1))
                        && Character.isLowSurrogate(query.charAt(end))) {
                    end--;
                }
                chunks.add(query.substring(start, end));
                start = end;
            }
            payload.put("queryLength", query.length());
            payload.put("queryChunked", true);
            // The local list never escapes elsewhere; an unmodifiable view
            // avoids copying all chunk references into a second list.
            payload.put("queryChunks", Collections.unmodifiableList(chunks));
        }
        logger.log(level, new ObjectMessage(Collections.unmodifiableMap(payload)), error);
    }
}
