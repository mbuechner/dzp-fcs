package de.ddb.labs.dzpfcs.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import okhttp3.HttpUrl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResultsEntryTest {

    private static final String DDB_ID = "0123456789abcdef0123456789abcdef";

    @Test
    void preservesQueryTextWithoutInjectingUrlParametersOrFragments() {
        final ResultsEntry entry = entryWithPageNumber("3");
        final String query = "\"Größe + Köln & issuepage=99#Fragment?x=100%\"";

        final HttpUrl url = HttpUrl.get(entry.getDzpUrl(query));

        assertEquals("https", url.scheme());
        assertEquals("www.deutsche-digitale-bibliothek.de", url.host());
        assertEquals(List.of("newspaper", "item", DDB_ID), url.pathSegments());
        assertEquals(query, url.queryParameter("query"));
        assertEquals("3", url.queryParameter("issuepage"));
        assertEquals(Set.of("query", "issuepage"), url.queryParameterNames());
        assertEquals(2, url.querySize());
        assertNull(url.fragment());
    }

    @Test
    void preservesPlaceholderLikeTextInQuery() {
        final ResultsEntry entry = entryWithPageNumber("8");
        final String query = "{{ddbid}} {{query}} {{pagenumber}} {{rows}} {{start}}";

        final HttpUrl url = HttpUrl.get(entry.getDzpUrl(query));

        assertEquals(query, url.queryParameter("query"));
        assertEquals("8", url.queryParameter("issuepage"));
    }

    @Test
    void omitsPageNumberWhenMissingOrBlank() {
        for (String pageNumber : new String[]{null, "", " \t"}) {
            final HttpUrl url = HttpUrl.get(entryWithPageNumber(pageNumber).getDzpUrl("Berlin"));

            assertEquals("Berlin", url.queryParameter("query"));
            assertEquals(Set.of("query"), url.queryParameterNames());
            assertEquals(1, url.querySize());
            assertNull(url.queryParameter("issuepage"));
        }
    }

    @Test
    void encodesPageNumberWithoutAddingParameters() {
        final String pageNumber = "1&query=other#fragment";
        final HttpUrl url = HttpUrl.get(entryWithPageNumber(pageNumber).getDzpUrl("Berlin"));

        assertEquals(pageNumber, url.queryParameter("issuepage"));
        assertEquals("Berlin", url.queryParameter("query"));
        assertEquals(2, url.querySize());
        assertNull(url.fragment());
    }

    @Test
    void snippetSnapshotsAreReadOnlyAndUnaffectedByInputChangesOrReplacement() {
        ResultsEntry entry = new ResultsEntry();
        List<String> input = new ArrayList<>(List.of("Vor <Hit>Berlin</Hit> & danach"));
        entry.setPlainpagefulltext(input);
        List<String> previous = entry.getPlainpagefulltext();

        input.clear();
        assertThrows(UnsupportedOperationException.class, () -> previous.add("untrusted"));
        entry.setPlainpagefulltext(List.of("<Hit>Hamburg</Hit>"));

        assertEquals(List.of("Vor <Hit>Berlin</Hit> & danach"), previous);
        assertEquals(List.of("<Hit>Hamburg</Hit>"), entry.getPlainpagefulltext());
    }

    @Test
    void acceptsItsCurrentSnippetSnapshotAsInputWithoutLosingHits() {
        ResultsEntry entry = new ResultsEntry();
        entry.setPlainpagefulltext(List.of("<Hit>Berlin</Hit>"));

        entry.setPlainpagefulltext(entry.getPlainpagefulltext());

        assertEquals(List.of("<Hit>Berlin</Hit>"), entry.getPlainpagefulltext());
    }

    @ParameterizedTest
    @MethodSource("unusableIdentifiers")
    void rejectsIdentifiersWhichCannotProduceAnUnchangedValidXmlReference(String id) {
        ResultsEntry entry = new ResultsEntry();
        entry.setId(id);

        assertThrows(IllegalArgumentException.class, entry::getDdbId);
        assertThrows(IllegalArgumentException.class, () -> entry.getDzpUrl("Berlin"));
    }

    static Stream<String> unusableIdentifiers() {
        return Stream.of(null, "", "short", " ".repeat(32), DDB_ID + "\u0000", DDB_ID + "\uFFFF",
                DDB_ID + "\uDC00", DDB_ID + "\uD800", "a".repeat(31) + "\uD83D\uDE80");
    }

    private static ResultsEntry entryWithPageNumber(String pageNumber) {
        final ResultsEntry entry = new ResultsEntry();
        entry.setId(DDB_ID + "_page_3");
        entry.setPagenumber(pageNumber);
        return entry;
    }
}
