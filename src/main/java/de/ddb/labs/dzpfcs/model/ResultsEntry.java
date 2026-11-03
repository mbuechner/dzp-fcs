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
package de.ddb.labs.dzpfcs.model;

import java.util.List;
import java.util.Objects;
import lombok.Getter;
import lombok.Setter;
import okhttp3.HttpUrl;

/**
 * Mutable backend document mapped and enriched within one search request.
 * Instances are not shared or mutated concurrently. Snippet lists are immutable
 * snapshots; XML normalization belongs to the output layer rather than this DTO.
 */
public class ResultsEntry {

    private static final HttpUrl DZP_URL = new HttpUrl.Builder()
            .scheme("https")
            .host("www.deutsche-digitale-bibliothek.de")
            .addPathSegment("newspaper")
            .addPathSegment("item")
            .build();
    @Getter
    @Setter
    private String id;
    @Getter
    @Setter
    private String pagenumber;
    private List<String> plainpagefulltext = List.of();

    /** Raw backend snippets, exposed read-only without allocating a copy. */
    public List<String> getPlainpagefulltext() {
        return plainpagefulltext;
    }

    /**
     * Keep the original text in an immutable snapshot. XML escaping and hit
     * interpretation belong to the output writer, avoiding repeated escaping
     * and parsing in this DTO. Previously returned snapshots remain unchanged;
     * the DTO's setters remain request-confined.
     */
    public void setPlainpagefulltext(List<String> snippets) {
        plainpagefulltext = List.copyOf(snippets);
    }

    /**
     * The first 32 UTF-16 units are the portal item identifier; any remaining
     * suffix identifies the backend page. Reject unusable IDs instead of failing
     * halfway through XML serialization or silently changing their characters.
     */
    public String getDdbId() {
        String value = id;
        if (value == null || value.length() < 32) {
            throw new IllegalArgumentException("Backend result ID must contain a 32-character item identifier.");
        }
        validateXmlIdentifier(value);
        String itemId = value.substring(0, 32);
        if (itemId.isBlank() || Character.isHighSurrogate(itemId.charAt(31))) {
            throw new IllegalArgumentException("Backend result ID has an unusable item identifier.");
        }
        return itemId;
    }

    /** Construct a landing-page link with independently encoded query and optional page parameters. */
    public String getDzpUrl(String query) {
        final HttpUrl.Builder url = DZP_URL.newBuilder()
                .addPathSegment(getDdbId())
                .addQueryParameter("query", Objects.requireNonNull(query));
        final String pageNumber = getPagenumber();
        if (pageNumber != null && !pageNumber.isBlank()) {
            url.addQueryParameter("issuepage", pageNumber);
        }
        return url.build().toString();
    }

    /** IDs are also emitted unchanged as XML attributes, so stripping invalid characters is unsafe. */
    private static void validateXmlIdentifier(String value) {
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (Character.isHighSurrogate(current) && i + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(i + 1))) {
                i++;
            } else if (Character.isSurrogate(current) || current == '\uFFFE' || current == '\uFFFF'
                    || (current < 0x20 && current != '\t' && current != '\n' && current != '\r')) {
                throw new IllegalArgumentException("Backend result ID contains a character forbidden in XML 1.0.");
            }
        }
    }
}
