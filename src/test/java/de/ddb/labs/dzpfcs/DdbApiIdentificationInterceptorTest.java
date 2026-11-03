package de.ddb.labs.dzpfcs;

import java.io.IOException;
import java.util.List;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DdbApiIdentificationInterceptorTest {

    private static final String CLIENT_ID = "newspaper-search";
    private static final String CLIENT_VERSION = "2026.11";
    private static final String CONTACT_EMAIL = "maintainer@example.org";
    private static final String CONTACT_URL = "https://example.org/contact";

    @ParameterizedTest
    @CsvSource({"0,0", "20,40"})
    void identifiesCountAndPaginatedRequestsWithoutChangingTheirQuery(int rows, int start) throws IOException {
        HttpUrl url = DzpEndpointSearchEngine.buildSearchUrl("https://example.invalid/solr/select",
                "\"Berlin & Hamburg\"", rows, start);
        Request original = new Request.Builder().url(url).header("Accept", "application/json").build();

        Request sent = send(new DdbApiIdentificationInterceptor(CLIENT_ID, CLIENT_VERSION, CONTACT_EMAIL, CONTACT_URL), original);

        assertEquals(CLIENT_ID + "/" + CLIENT_VERSION + " (+" + CONTACT_URL + "; mailto:" + CONTACT_EMAIL + ")", sent.header("User-Agent"));
        assertNull(sent.header("From"));
        assertEquals("application/json", sent.header("Accept"));
        assertEquals(original.url(), sent.url());
        assertEquals(original.url().encodedQuery(), sent.url().encodedQuery());
        assertEquals(Integer.toString(rows), sent.url().queryParameter("rows"));
        assertEquals(Integer.toString(start), sent.url().queryParameter("start"));
    }

    @Test
    void replacesOnlyTheUserAgentAndPreservesExistingFromHeaders() throws IOException {
        Request original = new Request.Builder()
                .url("https://example.invalid/search?q=Berlin&custom=keep%2Bme")
                .addHeader("User-Agent", "old-client")
                .addHeader("User-Agent", "another-client")
                .addHeader("From", "old@example.org")
                .addHeader("From", "another@example.org")
                .header("Accept", "application/json")
                .header("Cache-Control", "no-cache")
                .build();

        Request sent = send(new DdbApiIdentificationInterceptor("custom-client", "2.7.1", "custom@example.org",
                "https://example.org/custom"), original);

        assertEquals(List.of("custom-client/2.7.1 (+https://example.org/custom; mailto:custom@example.org)"),
                sent.headers("User-Agent"));
        assertEquals(original.headers("From"), sent.headers("From"));
        assertEquals(original.header("Accept"), sent.header("Accept"));
        assertEquals(original.header("Cache-Control"), sent.header("Cache-Control"));
        assertEquals(original.url(), sent.url());
        assertEquals(List.of("old-client", "another-client"), original.headers("User-Agent"));
    }

    @Test
    void escapesParenthesesAndBackslashesInTheUserAgentComment() throws IOException {
        DdbApiIdentificationInterceptor interceptor = new DdbApiIdentificationInterceptor(CLIENT_ID, CLIENT_VERSION,
                "ops(team)\\alias@example.org", "https://example.org/(team)/contact\\details");

        Request sent = send(interceptor, new Request.Builder().url("https://example.invalid/search").build());

        assertEquals("newspaper-search/2026.11 (+https://example.org/\\(team\\)/contact\\\\details; "
                + "mailto:ops\\(team\\)\\\\alias@example.org)", sent.header("User-Agent"));
        assertNull(sent.header("From"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" \t"})
    void rejectsMissingConfiguration(String invalid) {
        assertThrows(IllegalArgumentException.class,
                () -> new DdbApiIdentificationInterceptor(invalid, CLIENT_VERSION, CONTACT_EMAIL, CONTACT_URL));
        assertThrows(IllegalArgumentException.class,
                () -> new DdbApiIdentificationInterceptor(CLIENT_ID, invalid, CONTACT_EMAIL, CONTACT_URL));
        assertThrows(IllegalArgumentException.class,
                () -> new DdbApiIdentificationInterceptor(CLIENT_ID, CLIENT_VERSION, invalid, CONTACT_URL));
        assertThrows(IllegalArgumentException.class,
                () -> new DdbApiIdentificationInterceptor(CLIENT_ID, CLIENT_VERSION, CONTACT_EMAIL, invalid));
    }

    @ParameterizedTest
    @ValueSource(strings = {"client name", "client/1.0"})
    void rejectsClientIdsOrVersionsThatAreNotHttpTokens(String invalid) {
        assertThrows(IllegalArgumentException.class,
                () -> new DdbApiIdentificationInterceptor(invalid, CLIENT_VERSION, CONTACT_EMAIL, CONTACT_URL));
        assertThrows(IllegalArgumentException.class,
                () -> new DdbApiIdentificationInterceptor(CLIENT_ID, invalid, CONTACT_EMAIL, CONTACT_URL));
    }

    @ParameterizedTest
    @ValueSource(strings = {"contact\r\nInjected: yes"})
    void rejectsHeaderInjectionInEitherContactValue(String invalid) {
        assertThrows(IllegalArgumentException.class,
                () -> new DdbApiIdentificationInterceptor(CLIENT_ID, CLIENT_VERSION, invalid, CONTACT_URL));
        assertThrows(IllegalArgumentException.class,
                () -> new DdbApiIdentificationInterceptor(CLIENT_ID, CLIENT_VERSION, CONTACT_EMAIL, invalid));
    }

    private static Request send(DdbApiIdentificationInterceptor interceptor, Request request) throws IOException {
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(interceptor)
                .addInterceptor(chain -> new Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(ResponseBody.create("", null))
                        .build())
                .build();
        try (Response response = client.newCall(request).execute()) {
            return response.request();
        }
    }
}
