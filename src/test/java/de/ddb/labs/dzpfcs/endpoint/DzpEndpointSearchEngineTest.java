package de.ddb.labs.dzpfcs.endpoint;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.spi.json.Jackson3JsonProvider;
import com.jayway.jsonpath.spi.mapper.Jackson3MappingProvider;
import de.ddb.labs.dzpfcs.model.ResultsEntry;
import java.util.List;
import okhttp3.HttpUrl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DzpEndpointSearchEngineTest {

    private static final String ENDPOINT = "https://example.invalid/solr/select";
    private static final Configuration JSON_CONFIGURATION = Configuration.builder()
            .jsonProvider(new Jackson3JsonProvider())
            .mappingProvider(new Jackson3MappingProvider())
            .build();

    @ParameterizedTest
    @CsvSource({"0,0", "25,40"})
    void preservesSearchTextAndPaginationAsSeparateParameters(int rows, int start) {
        String query = "\"München a+b &rows=999 #fragment %20 {{query}} {{rows}} {{start}}\"";

        HttpUrl url = DzpEndpointSearchEngine.buildSearchUrl(ENDPOINT, query, rows, start);

        assertEquals(query, url.queryParameter("q"));
        assertEquals(List.of(query), url.queryParameterValues("q"));
        assertEquals(List.of(Integer.toString(rows)), url.queryParameterValues("rows"));
        assertEquals(List.of(Integer.toString(start)), url.queryParameterValues("start"));
        assertNull(url.fragment());
        assertEquals("example.invalid", url.host());
        assertEquals("/solr/select", url.encodedPath());
        assertEquals("<Hit>", url.queryParameter("hl.simple.pre"));
        assertEquals("</Hit>", url.queryParameter("hl.simple.post"));
        assertEquals("plainpagefulltext", url.queryParameter("df"));
        assertEquals("id,paper_title,pagenumber", url.queryParameter("fl"));
        assertEquals("true", url.queryParameter("hl"));
    }

    @Test
    void preservesEndpointOptionsAndReplacesExistingSearchParameters() {
        String endpoint = ENDPOINT + "?custom=keep%2Bme&q=old&rows=99&rows=98&start=90";

        HttpUrl url = DzpEndpointSearchEngine.buildSearchUrl(endpoint, "\"Berlin\"", 10, 20);

        assertEquals("keep+me", url.queryParameter("custom"));
        assertEquals(List.of("\"Berlin\""), url.queryParameterValues("q"));
        assertEquals(List.of("10"), url.queryParameterValues("rows"));
        assertEquals(List.of("20"), url.queryParameterValues("start"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        ",\"highlighting\":null",
        ",\"highlighting\":{}",
        ",\"highlighting\":{\"record\":null}",
        ",\"highlighting\":{\"record\":{}}",
        ",\"highlighting\":{\"record\":{\"plainpagefulltext\":null}}",
        ",\"highlighting\":{\"record\":{\"plainpagefulltext\":[]}}"
    })
    void retainsRecordsWhenHighlightsAreAbsentOrEmpty(String highlighting) {
        String json = "{\"response\":{\"docs\":[{\"id\":\"record\",\"pagenumber\":\"1\"}]}"
                + highlighting + "}";

        List<ResultsEntry> records = readResults(json);

        assertEquals(1, records.size());
        assertEquals("record", records.get(0).getId());
        assertEquals("1", records.get(0).getPagenumber());
        assertTrue(records.get(0).getPlainpagefulltext().isEmpty());
    }

    @Test
    void attachesOnlyTheHighlightsOfTheMatchingRecord() {
        String json = """
                {
                  "response": {
                    "docs": [{"id":"first"}, {"id":"second"}, {"id":"quote'id"}]
                  },
                  "highlighting": {
                    "first": {"plainpagefulltext": ["<Hit>Berlin</Hit> & Hamburg"]},
                    "quote'id": {"plainpagefulltext": ["<Hit>München</Hit>"]}
                  }
                }
                """;

        List<ResultsEntry> records = readResults(json);

        assertEquals(List.of("first", "second", "quote'id"),
                records.stream().map(ResultsEntry::getId).toList());
        assertEquals(List.of("<Hit>Berlin</Hit> &amp; Hamburg"), records.get(0).getPlainpagefulltext());
        assertTrue(records.get(1).getPlainpagefulltext().isEmpty());
        assertEquals(List.of("<Hit>München</Hit>"), records.get(2).getPlainpagefulltext());
    }

    private static List<ResultsEntry> readResults(String json) {
        return DzpEndpointSearchEngine.readResults(JsonPath.using(JSON_CONFIGURATION).parse(json));
    }
}
