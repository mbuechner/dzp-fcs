package de.ddb.labs.dzpfcs.query;

import eu.clarin.sru.server.SRUException;
import org.junit.jupiter.api.Test;
import org.z3950.zing.cql.CQLParser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CQLToSolrConverterTest {

    @Test
    void wrapsAnOrdinaryTermInASolrPhrase() throws Exception {
        assertEquals("\"Berlin\"", convert("Berlin"));
    }

    @Test
    void preservesSpacesAndUnicodeInAPhrase() throws Exception {
        assertEquals("\"Berlin ist schön\"", convert("\"Berlin ist schön\""));
    }

    @Test
    void escapesQuotesInsideATerm() throws Exception {
        assertEquals("\"Er sagte \\\"Hallo\\\"\"", convert("\"Er sagte \\\"Hallo\\\"\""));
    }

    @Test
    void escapesBackslashesInsideATerm() throws Exception {
        assertEquals("\"a\\\\\\\\b\"", convert("\"a\\\\b\""));
    }

    @Test
    void keepsSolrBooleanSyntaxInsideTheOriginalPhrase() throws Exception {
        assertEquals("\"x\\\" OR *:* OR \\\"x\"", convert("\"x\\\" OR *:* OR \\\"x\""));
    }

    @Test
    void preservesNestedAndOrExpressions() throws Exception {
        assertEquals("(\"Berlin\" AND (\"Hamburg\" OR \"München\"))",
                convert("Berlin AND (Hamburg OR München)"));
    }

    @Test
    void stillRejectsUnsupportedIndexes() {
        assertThrows(SRUException.class, () -> convert("dc.title = Berlin"));
    }

    @Test
    void stillRejectsUnsupportedBooleanOperators() {
        assertThrows(SRUException.class, () -> convert("Berlin NOT Hamburg"));
    }

    private static String convert(String cql) throws Exception {
        return CQLToSolrConverter.convertCQLtoSolrQuery(new CQLParser().parse(cql));
    }
}
