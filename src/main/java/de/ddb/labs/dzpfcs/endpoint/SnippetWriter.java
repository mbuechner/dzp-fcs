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

import java.util.Locale;
import java.util.Objects;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

/**
 * Writes the backend's literal highlight markers directly into an FCS Hits view.
 * This is a serializer for the known {@code <Hit>} and {@code </Hit>} markers,
 * not a general XML parser. Other tags and entity-looking strings remain text.
 * The utility is stateless; prepared snippets are immutable and safe to reuse.
 */
final class SnippetWriter {

    private static final String OPEN = "<Hit>";
    private static final String CLOSE = "</Hit>";

    private SnippetWriter() {
    }

    /**
     * Preserve only the first literal opening and closing markers as one Hit.
     * Later markers disappear, but their enclosed text remains. Missing partners
     * and a first closing marker before the first opening marker are rejected,
     * matching the former XML 1.0 SAX path rather than repairing malformed data.
     * A missing or blank first Hit cannot form the mandatory HITS payload and is
     * rejected before writing, so the record layer can emit a surrogate diagnostic.
     *
     * <p>The former XML 1.1 escaping removed NUL, isolated UTF-16 surrogates and
     * U+FFFE/U+FFFF before finding markers. We retain that order, while rejecting
     * controls which the subsequent XML 1.0 parse rejected. Surrogate pairs are
     * recognized in the original input, so deleting another character cannot
     * accidentally join two isolated surrogates into a supplementary character.
     * CRLF and CR become LF only after later markers have been removed.</p>
     *
     * @param writer destination with the FCS Hits namespace already bound by its DataView
     * @param rawSnippet unescaped backend text, including any literal hit markers
     * @throws XMLStreamException for a missing/blank Hit, malformed markers, XML 1.0 forbidden controls,
     *         or a failure from the destination writer
     */
    static void write(XMLStreamWriter writer, String rawSnippet) throws XMLStreamException {
        Objects.requireNonNull(writer, "writer");
        prepare(rawSnippet).write(writer);
    }

    /**
     * Validate and normalize before any XML is emitted. Result sets reuse this
     * immutable value between their surrogate check and payload serialization.
     * Only the first marked hit is eligible; later snippets or markers cannot
     * silently replace a missing first hit.
     */
    static PreparedSnippet prepare(String rawSnippet) throws XMLStreamException {
        String text = removeIllegalCharacters(Objects.requireNonNull(rawSnippet, "rawSnippet"));
        int opening = text.indexOf(OPEN);
        int closing = text.indexOf(CLOSE);
        if ((opening < 0) != (closing < 0) || (closing >= 0 && closing < opening)) {
            throw new XMLStreamException("Unbalanced or out-of-order backend Hit markers");
        }
        if (opening < 0) {
            throw new XMLStreamException("Backend snippet contains no Hit marker");
        }

        String hit = normalizeText(text.substring(opening + OPEN.length(), closing));
        if (hit.isBlank()) {
            // Clients may normalize HITS whitespace. Whitespace alone cannot
            // supply a meaningful highlight; preserve it around real hit text.
            throw new XMLStreamException("Backend snippet contains an empty or whitespace-only Hit");
        }
        return new PreparedSnippet(normalizeText(text.substring(0, opening)), hit,
                normalizeText(text.substring(closing + CLOSE.length())));
    }

    /** Remove only characters formerly discarded by escapeXml11; validate the rest for XML 1.0. */
    private static String removeIllegalCharacters(String input) throws XMLStreamException {
        StringBuilder clean = null;
        for (int i = 0; i < input.length(); i++) {
            char current = input.charAt(i);
            if (Character.isHighSurrogate(current)) {
                if (i + 1 < input.length() && Character.isLowSurrogate(input.charAt(i + 1))) {
                    if (clean != null) {
                        clean.append(current).append(input.charAt(i + 1));
                    }
                    i++;
                    continue;
                }
            }
            if (current == 0 || current == '\uFFFE' || current == '\uFFFF' || Character.isSurrogate(current)) {
                if (clean == null) {
                    // Most snippets need no character removal, so retain their
                    // original immutable String instead of allocating a copy.
                    clean = new StringBuilder(input.length()).append(input, 0, i);
                }
                continue;
            }
            if (current < 0x20 && current != '\t' && current != '\n' && current != '\r') {
                throw new XMLStreamException("Backend snippet contains an XML 1.0 forbidden control: U+"
                        + Integer.toHexString(current).toUpperCase(Locale.ROOT));
            }
            if (clean != null) {
                clean.append(current);
            }
        }
        return clean == null ? input : clean.toString();
    }

    /**
     * These literal replacements preserve the old ordering: removing an extra
     * opening marker may form a closing marker which the second pass also removes.
     * Newly formed opening markers remain text. Retained tags separate text runs,
     * while removed tags may join CR and LF into one normalized newline.
     */
    private static String normalizeText(String text) {
        return text.replace(OPEN, "").replace(CLOSE, "")
                .replace("\r\n", "\n").replace('\r', '\n');
    }

    /** Validated text runs preserve the old output while avoiding a second parse. */
    static final class PreparedSnippet {
        private final String before;
        private final String hit;
        private final String after;

        private PreparedSnippet(String before, String hit, String after) {
            this.before = before;
            this.hit = hit;
            this.after = after;
        }

        void write(XMLStreamWriter writer) throws XMLStreamException {
            Objects.requireNonNull(writer, "writer");
            writeText(writer, before);
            writer.writeStartElement(DzpConstants.FCS_HITS_NS, "Hit");
            writer.writeCharacters(hit);
            writer.writeEndElement();
            writeText(writer, after);
        }

        private static void writeText(XMLStreamWriter writer, String text) throws XMLStreamException {
            if (!text.isEmpty()) {
                writer.writeCharacters(text);
            }
        }
    }
}
