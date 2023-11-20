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
package de.ddb.labs.dzpfcs.query;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.z3950.zing.cql.CQLAndNode;
import org.z3950.zing.cql.CQLBooleanNode;
import org.z3950.zing.cql.CQLNode;
import org.z3950.zing.cql.CQLOrNode;
import org.z3950.zing.cql.CQLTermNode;

import eu.clarin.sru.server.SRUConstants;
import eu.clarin.sru.server.SRUException;
import eu.clarin.sru.server.fcs.parser.QueryParserException;

/**
 * Source: https://gist.github.com/Querela/825a084f94b30de88827050eddc8e361#file-cqltosolrconverter-java
 * @author Erik Körner <https://github.com/Querela/>
 */
public class CQLToSolrConverter {

    private static final Logger LOGGER = LogManager.getLogger(CQLToSolrConverter.class);

    public static String convertCQLtoSolrQuery(final CQLNode node)
            throws QueryParserException, SRUException {
        StringBuilder sb = new StringBuilder();

        convertCQLtoSolrSingle(node, sb);

        return sb.toString();
    }

    private static void convertCQLtoSolrSingle(final CQLNode node, StringBuilder sb) throws SRUException {
        if (node instanceof CQLTermNode) {
            final CQLTermNode tn = ((CQLTermNode) node);
            if (tn.getIndex() != null && !"cql.serverChoice".equalsIgnoreCase(tn.getIndex())) {
                throw new SRUException(SRUConstants.SRU_CANNOT_PROCESS_QUERY_REASON_UNKNOWN,
                        "Queries with queryType 'cql' do not support index/relation on '"
                        + node.getClass().getSimpleName() + "' by this FCS Endpoint.");
            }
            sb.append('"');
            sb.append(tn.getTerm());
            sb.append('"');
        } else if (node instanceof CQLOrNode || node instanceof CQLAndNode) {
            final CQLBooleanNode bn = (CQLBooleanNode) node;
            if (!bn.getModifiers().isEmpty()) {
                throw new SRUException(SRUConstants.SRU_CANNOT_PROCESS_QUERY_REASON_UNKNOWN,
                        "Queries with queryType 'cql' do not support modifiers on '" + node.getClass().getSimpleName() + "' by this FCS Endpoint.");
            }
            sb.append("(");
            convertCQLtoSolrSingle(bn.getLeftOperand(), sb);
            if (node instanceof CQLOrNode) {
                sb.append(" OR ");
            } else if (node instanceof CQLAndNode) {
                sb.append(" AND ");
            }
            convertCQLtoSolrSingle(bn.getRightOperand(), sb);
            sb.append(")");
        } else {
            throw new SRUException(SRUConstants.SRU_CANNOT_PROCESS_QUERY_REASON_UNKNOWN,
                    "Queries with queryType 'cql' do not support '" + node.getClass().getSimpleName() + "' by this FCS Endpoint.");
        }
    }

}
