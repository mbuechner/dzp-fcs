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

/**
 * One result page with a stable, read-only list structure. The list is copied
 * once on construction; its entries remain request-scoped mutable DTOs.
 */
@Getter
public final class Results {

    private final String pid;
    private final String query;
    private final List<ResultsEntry> results;
    private final long total;
    private final long offset;

    public Results(String pid, String query, List<ResultsEntry> results, long total, long offset) {
        this.pid = Objects.requireNonNull(pid, "pid");
        this.query = Objects.requireNonNull(query, "query");
        this.results = List.copyOf(results);
        this.total = total;
        this.offset = offset;
    }

    /**
     * Return the stored page without copying it. Constant-time size/index access
     * keeps SRU iteration linear instead of copying the whole page per record.
     */
    public List<ResultsEntry> getResults() {
        return results;
    }
}
