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
package de.ddb.labs.dzpfcs.logging;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Emits exactly one structured completion event for each synchronous HTTP request.
 *
 * <p>The server generates its own request ID and returns it in {@code X-Request-ID}.
 * Client-supplied IDs are logged separately, so they cannot overwrite correlation
 * IDs. The decoded query and User-Agent are intentionally recorded for usage
 * statistics; the JSON layout escapes control characters without truncating the
 * search term or changing it. No request body, cookie or authorization header is
 * logged.</p>
 *
 * <p>{@code clientAddress} is the address supplied by the servlet container.
 * {@code forwardedFor} is a separate, unverified header value. A trusted
 * container/proxy configuration, rather than this filter, must establish a real
 * client IP behind OpenShift. Durations cover the filter chain including SRU
 * serialization, not the time until a remote client has received every byte.</p>
 *
 * <p>This filter is mapped to synchronous {@code REQUEST} dispatches. Its
 * telemetry and MDC scope belong to the calling thread; asynchronous servlet
 * dispatch would require a separate lifecycle. The SRU serializer writes to the
 * output stream, which is observed directly without buffering response data.</p>
 */
public final class RequestLoggingFilter implements Filter {

    private static final Logger LOGGER = LogManager.getLogger(RequestLoggingFilter.class);

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest httpRequest)
                || !(response instanceof HttpServletResponse httpResponse)) {
            chain.doFilter(request, response);
            return;
        }

        long startedAt = System.nanoTime();
        String requestId = UUID.randomUUID().toString();
        try (RequestTelemetry.Scope scope = RequestTelemetry.open(requestId)) {
            ObservedResponse observed = new ObservedResponse(httpResponse, requestId);
            Map<String, Object> fields = new LinkedHashMap<>();
            Throwable failure = null;
            try {
                httpResponse.setHeader("X-Request-ID", requestId);
                fields.put("httpMethod", httpRequest.getMethod());
                fields.put("path", httpRequest.getRequestURI());
                fields.put("clientUserAgent", httpRequest.getHeader("User-Agent"));
                fields.put("clientAddress", httpRequest.getRemoteAddr());
                fields.put("forwardedFor", httpRequest.getHeader("X-Forwarded-For"));
                fields.put("upstreamRequestId", httpRequest.getHeader("X-Request-ID"));
                chain.doFilter(request, observed);
            } catch (IOException | ServletException | RuntimeException | Error e) {
                failure = e;
                throw e;
            } finally {
                // Read form parameters only after the servlet has chosen its encoding
                // and parsed the request. Logging must not change POST query decoding.
                try {
                    fields.put("operation", httpRequest.getParameter("operation"));
                    fields.put("query", httpRequest.getParameter("query"));
                    fields.put("queryType", httpRequest.getParameter("queryType"));
                    fields.put("startRecord", httpRequest.getParameter("startRecord"));
                    fields.put("maximumRecords", httpRequest.getParameter("maximumRecords"));
                } catch (RuntimeException metadataFailure) {
                    fields.put("parameterLogErrorClass", metadataFailure.getClass().getName());
                }
                scope.appendFieldsTo(fields);
                fields.put("requestDurationMs", RequestTelemetry.millis(System.nanoTime() - startedAt));
                int status = httpResponse.getStatus();
                fields.put("httpStatus", status);
                fields.put("chainOutcome", failure == null ? "returned" : "threw");
                fields.put("responseBytes", observed.bytesWritten);
                fields.put("responseWriteErrorClass", observed.writeFailure == null
                        ? null : observed.writeFailure.getClass().getName());
                fields.put("errorClass", failure == null ? null : failure.getClass().getName());
                boolean backendFailure = fields.get("backendOutcome") != null
                        && !"success".equals(fields.get("backendOutcome"));
                boolean hasWarning = (Integer) fields.get("backendTimeouts") > 0 || backendFailure
                        || Boolean.TRUE.equals(fields.get("solrPartialResults"))
                        || "diagnostic".equals(fields.get("engineOutcome")) || status >= 400;
                Level level = failure != null || status >= 500 ? Level.ERROR
                        : hasWarning || observed.writeFailure != null ? Level.WARN : Level.INFO;
                StructuredLog.event(LOGGER, level, "request.completed", fields, failure);
            }
        }
    }

    /**
     * Observe writes without buffering or interpreting XML. This also detects
     * I/O failures that the SRU framework catches internally after serialization.
     * Counts include completed output-stream writes since the last successful
     * reset, not confirmed delivery. A write that partially succeeds before
     * throwing cannot report its accepted byte count, so a recorded I/O failure
     * can make this a lower bound. Character-writer output is outside the SRU
     * serializer's byte-stream path and is not counted.
     */
    private static final class ObservedResponse extends HttpServletResponseWrapper {
        private final String requestId;
        private ServletOutputStream output;
        private long bytesWritten;
        private IOException writeFailure;

        private ObservedResponse(HttpServletResponse response, String requestId) {
            super(response);
            this.requestId = requestId;
        }

        @Override
        public void reset() {
            super.reset();
            setHeader("X-Request-ID", requestId);
            // Servlet reset also clears the selected stream/writer. Reacquire
            // the delegate on the next access rather than reuse a stale handle.
            output = null;
            bytesWritten = 0;
        }

        @Override
        public void resetBuffer() {
            super.resetBuffer();
            bytesWritten = 0;
        }

        @Override
        public void flushBuffer() throws IOException {
            try {
                super.flushBuffer();
            } catch (IOException e) {
                writeFailure = e;
                throw e;
            }
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            if (output == null) {
                ServletOutputStream delegate;
                try {
                    delegate = super.getOutputStream();
                } catch (IOException e) {
                    writeFailure = e;
                    throw e;
                }
                output = new ServletOutputStream() {
                    @Override
                    public boolean isReady() {
                        return delegate.isReady();
                    }

                    @Override
                    public void setWriteListener(WriteListener listener) {
                        delegate.setWriteListener(listener);
                    }

                    @Override
                    public void write(int value) throws IOException {
                        try {
                            delegate.write(value);
                            bytesWritten++;
                        } catch (IOException e) {
                            writeFailure = e;
                            throw e;
                        }
                    }

                    @Override
                    public void write(byte[] bytes, int offset, int length) throws IOException {
                        try {
                            delegate.write(bytes, offset, length);
                            bytesWritten += length;
                        } catch (IOException e) {
                            writeFailure = e;
                            throw e;
                        }
                    }

                    @Override
                    public void flush() throws IOException {
                        try {
                            delegate.flush();
                        } catch (IOException e) {
                            writeFailure = e;
                            throw e;
                        }
                    }

                    @Override
                    public void close() throws IOException {
                        try {
                            delegate.close();
                        } catch (IOException e) {
                            writeFailure = e;
                            throw e;
                        }
                    }
                };
            }
            return output;
        }
    }
}
