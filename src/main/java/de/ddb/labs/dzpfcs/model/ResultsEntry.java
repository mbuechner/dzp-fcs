/*
 * Copyright 2023-2025 Michael Büchner, Deutsche Digitale Bibliothek
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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.Getter;
import lombok.Setter;
import okhttp3.HttpUrl;
import org.apache.commons.text.StringEscapeUtils;

public class ResultsEntry {

    private static final HttpUrl DZP_URL = new HttpUrl.Builder()
            .scheme("https")
            .host("www.deutsche-digitale-bibliothek.de")
            .addPathSegment("newspaper")
            .addPathSegment("item")
            .build();
    @Getter
    @Setter
    private String id, pagenumber, paper_title;
    private final List<String> plainpagefulltext = new ArrayList<>();

    public List<String> getPlainpagefulltext() {
        return new ArrayList<>(plainpagefulltext);
    }

    public void setPlainpagefulltext(List<String> ppft) {
        plainpagefulltext.clear();

        for (String v : ppft) {
            String t = StringEscapeUtils.escapeXml11(v);
            t = t.replaceFirst("&lt;Hit&gt;", "<Hit>");
            t = t.replaceFirst("&lt;/Hit&gt;", "</Hit>");
            t = t.replaceAll("&lt;Hit&gt;", "");
            t = t.replaceAll("&lt;/Hit&gt;", "");
            plainpagefulltext.add(t);
        }
    }

    @Override
    public String toString() {
        return ("[id=" + id + ", pagenumber=" + pagenumber + ", paper_title=" + paper_title + ", plainpagefulltext=" + plainpagefulltext.toString() + "]");
    }

    public String getDdbId() {
        return id.substring(0, 32);
    }

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
}
