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
package de.ddb.labs.dzpfcs.searcher;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;

public class MyResults {
    @Getter
    private final String pid;
    @Getter
    private final String query;
    private final List<ResultEntry> results;
    @Getter
    private final long total;
    @Getter
    private final long offset;
    

    public MyResults(String pid, String query, List<ResultEntry> results, long total, long offset) {
        this.pid = pid;
        this.query = query;
        this.results = new ArrayList<>(results);
        this.total = total;
        this.offset = offset;
    }

    public List<ResultEntry> getResults() {
        return new ArrayList<>(results);
    }  

    /**
     * Minimal single result entry. Consisting of only a text and
     * backlink to the result (if available).
     */
    public static class ResultEntry {
        public String text;
        public String landingpage;
        public String pid;
    
        @Override
        public String toString() {
            return "ResultEntry [text=" + text + "]";
        }
    }
}
