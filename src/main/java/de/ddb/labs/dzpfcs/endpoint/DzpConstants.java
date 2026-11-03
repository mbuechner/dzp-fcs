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

/** Request parameter names and wire identifiers for the supported Basic Search/HITS profile. */
public final class DzpConstants {

    private DzpConstants() {
    }

    // FCS request parameters to extract Resource PIDs
    public static final String X_FCS_CONTEXT_KEY = "x-fcs-context";
    public static final String X_FCS_CONTEXT_SEPARATOR = ",";
    // FCS request parameters to extract Data Views
    public static final String X_FCS_DATAVIEWS_KEY = "x-fcs-dataviews";
    public static final String X_FCS_DATAVIEWS_SEPARATOR = ",";
    public static final String CLARIN_FCS_RECORD_SCHEMA = "http://clarin.eu/fcs/resource";
    public static final String FCS_HITS_MIMETYPE = "application/x-clarin-fcs-hits+xml";
    public static final String FCS_HITS_PREFIX = "hits";
    public static final String FCS_HITS_NS = "http://clarin.eu/fcs/dataview/hits";
}
