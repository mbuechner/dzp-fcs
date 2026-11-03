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

import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;

/**
 * Immutable configuration for backend calls, their retry policy and concurrency limit.
 *
 * <p>Environment timeout values use whole seconds; backoff values use milliseconds.
 * Defaults are a 10-second connection timeout, 50-second read inactivity timeout,
 * 50-second backend budget, three application-level attempts, 16 concurrent calls,
 * and client-generated backoff starting at one second and capped at ten seconds.
 *
 * <p>The backend budget includes waiting for a concurrency permit, all attempts,
 * response-body reads and retry delays. Unlike the read timeout, it does not restart
 * when more response data arrives. The 50-second default is configured below the
 * deployment's 60-second OpenShift route timeout to allow additional processing.
 * It neither guarantees remaining processing time nor imposes an end-to-end
 * deadline on the servlet response; cancellation and synchronous logging can
 * also finish later than the budget.
 *
 * <p>The concurrency limit belongs to one executor instance and includes body reads,
 * but not retry backoff. Attempts count application-level calls: OkHttp can perform
 * additional connection recovery or HTTP follow-ups within a call's remaining budget.
 * The maximum retry delay caps client-generated backoff only; a server's
 * {@code Retry-After} remains an independent minimum waiting time.
 *
 * @param connectTimeout positive timeout for establishing an individual connection
 * @param readTimeout positive inactivity timeout for individual response read operations
 * @param requestTimeout positive total backend budget shared by queueing and all attempts
 * @param maxAttempts positive maximum number of application-level attempts, including the first
 * @param maxConcurrentRequests positive maximum number of simultaneous calls per executor instance
 * @param retryDelay non-negative initial client-generated retry backoff; zero disables backoff
 * @param maxRetryDelay non-negative cap for client-generated backoff, at least {@code retryDelay}
 */
public record BackendHttpSettings(Duration connectTimeout, Duration readTimeout, Duration requestTimeout,
        int maxAttempts, int maxConcurrentRequests, Duration retryDelay, Duration maxRetryDelay) {

    private static final String PREFIX = "DZP_FCS_BACKEND_";
    private static final String CONNECT_TIMEOUT = PREFIX + "CONNECT_TIMEOUT_SECONDS";
    private static final String READ_TIMEOUT = PREFIX + "READ_TIMEOUT_SECONDS";
    private static final String REQUEST_TIMEOUT = PREFIX + "REQUEST_TIMEOUT_SECONDS";
    private static final String MAX_ATTEMPTS = PREFIX + "MAX_ATTEMPTS";
    private static final String MAX_CONCURRENT_REQUESTS = PREFIX + "MAX_CONCURRENT_REQUESTS";
    private static final String RETRY_DELAY = PREFIX + "RETRY_DELAY_MILLIS";
    private static final String MAX_RETRY_DELAY = PREFIX + "MAX_RETRY_DELAY_MILLIS";

    /**
     * Validates both directly constructed settings and values loaded from configuration.
     * Invalid values fail before a request is sent, with the relevant environment
     * variable named in the exception so startup failures can be corrected directly.
     *
     * @throws IllegalArgumentException if a value is missing, outside its supported
     *         range, or inconsistent with the maximum backoff
     */
    public BackendHttpSettings {
        validateDuration(connectTimeout, CONNECT_TIMEOUT, false);
        validateDuration(readTimeout, READ_TIMEOUT, false);
        validateDuration(requestTimeout, REQUEST_TIMEOUT, false);
        validateOkHttpTimeout(connectTimeout, CONNECT_TIMEOUT);
        validateOkHttpTimeout(readTimeout, READ_TIMEOUT);
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException(MAX_ATTEMPTS + " must be positive");
        }
        if (maxConcurrentRequests <= 0) {
            throw new IllegalArgumentException(MAX_CONCURRENT_REQUESTS + " must be positive");
        }
        validateDuration(retryDelay, RETRY_DELAY, true);
        validateDuration(maxRetryDelay, MAX_RETRY_DELAY, true);
        if (maxRetryDelay.compareTo(retryDelay) < 0) {
            throw new IllegalArgumentException(MAX_RETRY_DELAY + " must be at least " + RETRY_DELAY);
        }
    }

    /**
     * Reads settings through the application's configuration lookup and converts the
     * documented environment units to durations. The engine supplies {@code dotenv::get},
     * which resolves external environment overrides and bundled {@code .env} values.
     * A missing value ({@code null}) uses the fallback defaults above; a present but
     * blank, malformed or invalid value is rejected rather than silently defaulted.
     *
     * @param environment lookup returning a configured string or {@code null} when absent
     * @return validated settings ready to be shared by backend calls
     * @throws IllegalArgumentException if a configured value is invalid; its variable
     *         name is included in the exception message
     * @throws NullPointerException if the lookup itself is {@code null}
     */
    public static BackendHttpSettings from(Function<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        return new BackendHttpSettings(
                Duration.ofSeconds(readLong(environment, CONNECT_TIMEOUT, 10)),
                Duration.ofSeconds(readLong(environment, READ_TIMEOUT, 50)),
                Duration.ofSeconds(readLong(environment, REQUEST_TIMEOUT, 50)),
                readPositiveInt(environment, MAX_ATTEMPTS, 3),
                readPositiveInt(environment, MAX_CONCURRENT_REQUESTS, 16),
                Duration.ofMillis(readLong(environment, RETRY_DELAY, 1000)),
                Duration.ofMillis(readLong(environment, MAX_RETRY_DELAY, 10000)));
    }

    private static long readLong(Function<String, String> environment, String name, long defaultValue) {
        String value = environment.apply(name);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer within the supported range", e);
        }
    }

    private static int readPositiveInt(Function<String, String> environment, String name, int defaultValue) {
        long value = readLong(environment, name, defaultValue);
        if (value <= 0 || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(name + " must be between 1 and " + Integer.MAX_VALUE);
        }
        return (int) value;
    }

    private static void validateDuration(Duration value, String name, boolean allowZero) {
        if (value == null || value.isNegative() || (!allowZero && value.isZero())) {
            throw new IllegalArgumentException(name + (allowZero ? " must be non-negative" : " must be positive"));
        }
        try {
            // Budget and backoff calculations use nanoseconds, so reject overflow at startup.
            value.toNanos();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(name + " is too large to represent as nanoseconds", e);
        }
    }

    /**
     * Applies OkHttp's integer-millisecond bound to connection and read settings.
     * The total request budget intentionally has no such bound: the executor applies
     * its remaining duration directly to each call's nanosecond timeout.
     */
    private static void validateOkHttpTimeout(Duration value, String name) {
        if (value.compareTo(Duration.ofMillis(1)) < 0
                || value.compareTo(Duration.ofMillis(Integer.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException(name + " must be between 1 and " + Integer.MAX_VALUE
                    + " milliseconds for OkHttp");
        }
    }
}
