package de.ddb.labs.dzpfcs.query;

import eu.clarin.sru.server.SRUConstants;
import eu.clarin.sru.server.SRUException;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.z3950.zing.cql.CQLParser;
import org.z3950.zing.cql.CQLAndNode;
import org.z3950.zing.cql.CQLNode;
import org.z3950.zing.cql.CQLRelation;
import org.z3950.zing.cql.CQLTermNode;
import org.z3950.zing.cql.ModifierSet;

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
        assertDiagnostic("dc.title = Berlin", SRUConstants.SRU_UNSUPPORTED_INDEX, "dc.title");
    }

    @Test
    void stillRejectsUnsupportedBooleanOperators() {
        assertDiagnostic("Berlin NOT Hamburg", SRUConstants.SRU_UNSUPPORTED_BOOLEAN_OPERATOR, "not");
    }

    @Test
    void preservesTheNativeServerChoiceDefaultInBothCqlVersions() throws Exception {
        for (int version : new int[]{CQLParser.V1POINT1, CQLParser.V1POINT2}) {
            assertEquals("\"Berlin Hamburg\"", CQLToSolrConverter.convertCQLtoSolrQuery(
                    new CQLParser(version).parse("\"Berlin Hamburg\"")));
            assertEquals("\"Berlin Hamburg\"", CQLToSolrConverter.convertCQLtoSolrQuery(
                    new CQLParser(version).parse("cql.serverChoice = \"Berlin Hamburg\"")));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"=", "cql.scr", "CQL.SCR"})
    void allowsOnlyTheSupportedServerChoiceRelationSpellings(String relation) throws Exception {
        assertEquals("\"Berlin Hamburg\"", CQLToSolrConverter.convertCQLtoSolrQuery(
                new CQLParser(CQLParser.V1POINT2).parse(
                        "cql.serverChoice " + relation + " \"Berlin Hamburg\"")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"any", "all", "cql.any", "cql.all", "==", "cql.exact",
        "<", ">", "<=", ">=", "<>", "adj", "cql.adj", "other.scr"})
    void rejectsUnsupportedRelationsRatherThanChangingTheirMeaningToAPhrase(String relation) {
        assertDiagnostic("cql.serverChoice " + relation + " \"Berlin Hamburg\"",
                SRUConstants.SRU_UNSUPPORTED_RELATION, relation);
    }

    @ParameterizedTest
    @ValueSource(strings = {"string", "cql.string", "cql.ignoreCase", "cql.regexp", "other.fuzzy"})
    void rejectsUnqualifiedAndNamespacedRelationModifiers(String modifier) {
        assertDiagnostic("cql.serverChoice =/" + modifier + " \"Berlin Hamburg\"",
                SRUConstants.SRU_UNSUPPORTED_RELATION_MODIFIER, modifier.toLowerCase(Locale.ROOT));
    }

    @Test
    void rejectsAValuedRelationModifierWithoutIgnoringItsRequestedComparison() {
        assertDiagnostic("cql.serverChoice =/cql.locale=de \"Berlin\"",
                SRUConstants.SRU_UNSUPPORTED_RELATION_MODIFIER, "cql.locale");
    }

    @Test
    void validatesRelationsInBothBooleanOperands() {
        assertDiagnostic("Berlin AND cql.serverChoice any \"Hamburg München\"",
                SRUConstants.SRU_UNSUPPORTED_RELATION, "any");
        assertDiagnostic("cql.serverChoice =/cql.string Berlin OR Hamburg",
                SRUConstants.SRU_UNSUPPORTED_RELATION_MODIFIER, "cql.string");
    }

    @Test
    void rejectsBooleanModifiersWithTheirSpecificDiagnostic() {
        assertDiagnostic("Berlin AND/cql.combine=sum Hamburg",
                SRUConstants.SRU_UNSUPPORTED_BOOLEAN_MODIFIER, "cql.combine");
    }

    @Test
    void rejectsTheFormerlyAdvertisedButUnsupportedFcsWordsIndex() {
        assertDiagnostic("fcs.words = Berlin", SRUConstants.SRU_UNSUPPORTED_INDEX, "fcs.words");
    }

    @Test
    void convertsADeepValidBooleanAstWithoutUsingTheJavaCallStack() throws Exception {
        int depth = 10_000;
        CQLTermNode term = new CQLTermNode("cql.serverChoice", new CQLRelation("="), "x");
        CQLNode tree = term;
        for (int i = 0; i < depth; i++) {
            tree = new CQLAndNode(tree, term, new ModifierSet("and"));
        }

        assertEquals("(".repeat(depth) + "\"x\"" + " AND \"x\")".repeat(depth),
                CQLToSolrConverter.convertCQLtoSolrQuery(tree));
    }

    private static void assertDiagnostic(String cql, String uri, String details) {
        SRUException failure = assertThrows(SRUException.class, () -> convert(cql));
        assertEquals(uri, failure.getDiagnostic().getURI());
        assertEquals(details, failure.getDiagnostic().getDetails());
    }

    private static String convert(String cql) throws Exception {
        return CQLToSolrConverter.convertCQLtoSolrQuery(new CQLParser().parse(cql));
    }
}
