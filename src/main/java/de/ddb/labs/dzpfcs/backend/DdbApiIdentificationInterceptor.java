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
package de.ddb.labs.dzpfcs.backend;

import java.io.IOException;
import java.util.regex.Pattern;
import okhttp3.Headers;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Adds the application's versioned identity to every DDB API request.
 * The complete User-Agent is validated and built once during startup, so this
 * immutable interceptor can be shared by concurrent calls without per-call
 * token validation or access to environment variables. Other headers are kept.
 */
public final class DdbApiIdentificationInterceptor implements Interceptor {

    private static final Pattern HTTP_TOKEN = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");

    private final String userAgent;

    public DdbApiIdentificationInterceptor(String clientId, String clientVersion, String contactEmail, String contactUrl) {
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
