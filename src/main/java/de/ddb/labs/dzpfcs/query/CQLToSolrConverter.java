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
package de.ddb.labs.dzpfcs.query;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Objects;
import org.z3950.zing.cql.CQLAndNode;
import org.z3950.zing.cql.CQLBooleanNode;
import org.z3950.zing.cql.CQLNode;
import org.z3950.zing.cql.CQLOrNode;
import org.z3950.zing.cql.CQLRelation;
import org.z3950.zing.cql.CQLTermNode;
import eu.clarin.sru.server.SRUConstants;
import eu.clarin.sru.server.SRUException;

/**
 * Translate the supported Basic Search CQL subset into explicitly grouped Solr
 * phrase queries. Terms cannot escape their phrase through quotes or backslashes;
 * unsupported indexes, relations and modifiers retain their specific SRU diagnostics.
 * All state is local to a call, and the input AST is never modified.
 *
 * @see <a href="https://gist.github.com/Querela/825a084f94b30de88827050eddc8e361#file-cqltosolrconverter-java">Original converter by Erik Körner</a>
 */
public final class CQLToSolrConverter {

    private CQLToSolrConverter() {
    }

    /** Use an explicit traversal stack so deeply nested valid AND/OR trees cannot exhaust the Java stack. */
    public static String convertCQLtoSolrQuery(final CQLNode node) throws SRUException {
        StringBuilder query = new StringBuilder();
        Deque<Object> pending = new ArrayDeque<>();
        pending.push(Objects.requireNonNull(node, "node"));
        while (!pending.isEmpty()) {
            Object part = pending.pop();
            if (part instanceof Token token) {
                query.append(token.text);
            } else if (part instanceof CQLTermNode term) {
                appendTerm(term, query);
            } else if (part instanceof CQLBooleanNode booleanNode) {
                validateBoolean(booleanNode);
                // Stack order is reversed; output remains (left OP right).
                pending.push(Token.CLOSE);
                pending.push(booleanNode.getRightOperand());
                pending.push(booleanNode instanceof CQLOrNode ? Token.OR : Token.AND);
                pending.push(booleanNode.getLeftOperand());
                pending.push(Token.OPEN);
            } else {
                throw new SRUException(SRUConstants.SRU_CANNOT_PROCESS_QUERY_REASON_UNKNOWN,
                        "Unsupported CQL node: " + part.getClass().getSimpleName());
            }
        }
        return query.toString();
    }

    private static void appendTerm(CQLTermNode term, StringBuilder query) throws SRUException {
        if (term.getIndex() != null && !"cql.serverChoice".equalsIgnoreCase(term.getIndex())) {
            throw new SRUException(SRUConstants.SRU_UNSUPPORTED_INDEX, term.getIndex(),
                    "Only the cql.serverChoice index is supported.");
        }
        final CQLRelation relation = term.getRelation();
        final String relationName = relation.getBase();
        // '=' and its scr alias let the server choose phrase matching. Other
        // relations must not silently become phrases (notably any/all/exact).
        // Native CQL 1.1 and 1.2 parsers use different default spellings.
        if (!"=".equals(relationName) && !"scr".equalsIgnoreCase(relationName)
                && !"cql.scr".equalsIgnoreCase(relationName)) {
            throw new SRUException(SRUConstants.SRU_UNSUPPORTED_RELATION, relationName,
                    "Only the server-choice relation (= or scr) is supported.");
        }
        if (!relation.getModifiers().isEmpty()) {
            throw new SRUException(SRUConstants.SRU_UNSUPPORTED_RELATION_MODIFIER,
                    relation.getModifiers().get(0).getType(),
                    "CQL relation modifiers are not supported.");
        }
        query.append('"');
        String text = term.getTerm();
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (character == '\\' || character == '"') {
                query.append('\\');
            }
            query.append(character);
        }
        query.append('"');
    }

    private static void validateBoolean(CQLBooleanNode node) throws SRUException {
        if (!(node instanceof CQLOrNode || node instanceof CQLAndNode)) {
            throw new SRUException(SRUConstants.SRU_UNSUPPORTED_BOOLEAN_OPERATOR,
                    node.getOperator().toString().toLowerCase(Locale.ROOT),
                    "Only the AND and OR boolean operators are supported.");
        }
        if (!node.getModifiers().isEmpty()) {
            throw new SRUException(SRUConstants.SRU_UNSUPPORTED_BOOLEAN_MODIFIER,
                    node.getModifiers().get(0).getType(),
                    "CQL boolean modifiers are not supported.");
        }
    }

    /** Fixed output tokens share the traversal stack with input nodes without per-node wrapper allocations. */
    private enum Token {
        OPEN("("), CLOSE(")"), AND(" AND "), OR(" OR ");

        private final String text;

        Token(String text) {
            this.text = text;
        }
    }
}
