package de.ddb.labs.dzpfcs.backend;

import de.ddb.labs.dzpfcs.logging.RequestTelemetry;
import de.ddb.labs.dzpfcs.model.Results;
import de.ddb.labs.dzpfcs.model.ResultsEntry;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Protects independent response mapping through the shared Jackson reader.
 * Paging failures and malformed required fields are also exercised through the
 * full backend boundary in {@code DzpBackendSearchTest}; these tests focus on
 * reader reuse, accepted numeric boundaries and optional response metadata.
 */
class SolrResponseParserTest {

    private static final String FIRST_ID = "0123456789abcdef0123456789abcdef";
    private static final String SECOND_ID = "1123456789abcdef0123456789abcdef";
    private static final String QUOTED_ID = "quote'id0123456789abcdef0123456789abcdef";

    private static final String ID = "0123456789abcdef0123456789abcdef";

    @Test
    void concurrentReaderCallsKeepCorpusQueryOffsetDocumentsAndSnippetsIndependent() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(4);
        CountDownLatch ready = new CountDownLatch(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Void>> tasks = new ArrayList<>();
        try {
            for (int worker = 0; worker < 4; worker++) {
                int workerIndex = worker;
                tasks.add(workers.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    for (int iteration = 0; iteration < 30; iteration++) {
                        int key = workerIndex * 100 + iteration;
                        String id = "%032d".formatted(key);
                        String pid = "https://example.invalid/corpus/" + key;
                        String query = "\"needle " + key + " + &\"";
                        String snippet = "Before <Hit>needle " + key + "</Hit> after";
                        String json = """
                                {"response":{"numFound":1000,"docs":[{"id":"%s","pagenumber":%d}]},
                                 "highlighting":{"%s":{"plainpagefulltext":["%s"]}}}
                                """.formatted(id, key + 1, id, snippet);

                        Results first = SolrResponseParser.parse(json, pid, query, key);
                        Results next = SolrResponseParser.parse(json, pid + "/other", "other query", key + 1);
                        next.getResults().get(0).setPagenumber("mutated other result");

                        assertEquals(pid, first.getPid());
                        assertEquals(query, first.getQuery());
                        assertEquals(key, first.getOffset());
                        assertEquals(1000, first.getTotal());
                        assertEquals(1, first.getResults().size());
                        assertEquals(id, first.getResults().get(0).getId());
                        assertEquals(Integer.toString(key + 1), first.getResults().get(0).getPagenumber());
                        assertEquals(List.of(snippet), first.getResults().get(0).getPlainpagefulltext());
                        assertNotSame(first.getResults(), next.getResults());
                        assertNotSame(first.getResults().get(0), next.getResults().get(0));
                    }
                    return null;
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (Future<Void> task : tasks) {
                task.get(10, TimeUnit.SECONDS);
            }
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "27", "2147483648"})
    void preservesPositiveNumericPageNumbersInTheLandingPageLink(String page) throws Exception {
        Results result = SolrResponseParser.parse("""
                {"response":{"numFound":1,"docs":[{"id":"%s","pagenumber":%s}]}}
                """.formatted(ID, page), "corpus", "Berlin", 0);

        assertEquals(page, result.getResults().get(0).getPagenumber());
        assertTrue(URI.create(result.getResults().get(0).getDzpUrl("Berlin"))
                .getRawQuery().contains("issuepage=" + page));
    }

    @ParameterizedTest
    @CsvSource({"0,0,0", "12.0,12,12", "2147483647,2147483647,2147483647"})
    void acceptsExactNonNegativeCountsThroughTheLargestSruInteger(String count, int offset, long expected)
            throws Exception {
        Results result = SolrResponseParser.parse(
                "{\"response\":{\"numFound\":" + count + ",\"docs\":[]}}", "corpus", "Berlin", offset);

        assertEquals(expected, result.getTotal());
        assertEquals(offset, result.getOffset());
        assertTrue(result.getResults().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "null", "[]", "\"unexpected\"", "{}",
        "{\"QTime\":\"slow\",\"partialResults\":{\"unexpected\":true}}",
        "{\"QTime\":-1,\"partialResults\":37}",
        "{\"QTime\":1.5,\"partialResults\":[]}",
        "{\"QTime\":9223372036854775808,\"partialResults\":\"unexpected\"}"
    })
    void malformedOptionalTimingMetadataDoesNotInvalidateAnOtherwiseUsablePage(String header) throws Exception {
        try (RequestTelemetry.Scope scope = RequestTelemetry.open("optional-metadata")) {
            Results result = SolrResponseParser.parse("""
                    {"responseHeader":%s,"response":{"numFound":1,"docs":[{"id":"%s"}]}}
                    """.formatted(header, ID), "corpus", "Berlin", 0);

            assertEquals(1, result.getTotal());
            assertEquals(ID, result.getResults().get(0).getId());
            assertNull(scope.fields().get("solrQTimeMs"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "\"true\"", "\"omitted\""})
    void observesPartialResultMarkersWithoutDroppingTheReturnedPage(String partial) throws Exception {
        try (RequestTelemetry.Scope scope = RequestTelemetry.open("partial-metadata")) {
            Results result = SolrResponseParser.parse("""
                    {"responseHeader":{"partialResults":%s},
                     "response":{"numFound":1,"docs":[{"id":"%s"}]}}
                    """.formatted(partial, ID), "corpus", "Berlin", 0);

            assertEquals(1, result.getResults().size());
            assertEquals(true, scope.fields().get("solrPartialResults"));
        }
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
        String json = "{\"response\":{\"docs\":[{\"id\":\"" + FIRST_ID + "\",\"pagenumber\":\"1\"}]}"
                + highlighting.replace("\"record\"", "\"" + FIRST_ID + "\"") + "}";

        List<ResultsEntry> records = readResults(json);

        assertEquals(1, records.size());
        assertEquals(FIRST_ID, records.get(0).getId());
        assertEquals("1", records.get(0).getPagenumber());
        assertTrue(records.get(0).getPlainpagefulltext().isEmpty());
    }

    @Test
    void attachesOnlyTheHighlightsOfTheMatchingRecord() {
        String json = """
                {
                  "response": {
                    "docs": [{"id":"%s"}, {"id":"%s"}, {"id":"%s"}]
                  },
                  "highlighting": {
                    "%s": {"plainpagefulltext": ["<Hit>Berlin</Hit> & Hamburg"]},
                    "%s": {"plainpagefulltext": ["<Hit>München</Hit>"]}
                  }
                }
                """.formatted(FIRST_ID, SECOND_ID, QUOTED_ID, FIRST_ID, QUOTED_ID);

        List<ResultsEntry> records = readResults(json);

        assertEquals(List.of(FIRST_ID, SECOND_ID, QUOTED_ID),
                records.stream().map(ResultsEntry::getId).toList());
        assertEquals(List.of("<Hit>Berlin</Hit> & Hamburg"), records.get(0).getPlainpagefulltext());
        assertTrue(records.get(1).getPlainpagefulltext().isEmpty());
        assertEquals(List.of("<Hit>München</Hit>"), records.get(2).getPlainpagefulltext());
    }

    private static List<ResultsEntry> readResults(String json) {
        return SolrResponseParser.readRecords(tools.jackson.databind.json.JsonMapper.builder().build().readTree(json));
    }
}
