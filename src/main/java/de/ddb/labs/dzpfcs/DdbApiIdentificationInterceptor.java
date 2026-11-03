package de.ddb.labs.dzpfcs;

import java.io.IOException;
import java.util.regex.Pattern;
import okhttp3.Headers;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

final class DdbApiIdentificationInterceptor implements Interceptor {

    private static final Pattern HTTP_TOKEN = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");

    private final String userAgent;

    DdbApiIdentificationInterceptor(String clientId, String clientVersion, String contactEmail, String contactUrl) {
        requireNonBlank(clientId, "clientId");
        requireNonBlank(clientVersion, "clientVersion");
        requireNonBlank(contactEmail, "contactEmail");
        requireNonBlank(contactUrl, "contactUrl");
        if (!HTTP_TOKEN.matcher(clientId).matches()) {
            throw new IllegalArgumentException("clientId must be an HTTP token");
        }
        if (!HTTP_TOKEN.matcher(clientVersion).matches()) {
            throw new IllegalArgumentException("clientVersion must be an HTTP token");
        }

        // Validate configured values before the first request using OkHttp's header rules.
        Headers headers = new Headers.Builder()
                .set("User-Agent", clientId + "/" + clientVersion + " (+" + escapeComment(contactUrl)
                        + "; mailto:" + escapeComment(contactEmail) + ")")
                .build();
        this.userAgent = headers.get("User-Agent");
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request().newBuilder()
                .header("User-Agent", userAgent)
                .build();
        return chain.proceed(request);
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static String escapeComment(String value) {
        return value.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
    }
}
