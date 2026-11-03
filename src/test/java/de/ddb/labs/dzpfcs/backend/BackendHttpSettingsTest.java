package de.ddb.labs.dzpfcs.backend;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the configuration boundary independently of network execution: defaults,
 * environment overrides, supported numeric ranges and actionable validation errors.
 * Distinct timeout and backoff values protect the contract that environment timeouts
 * use seconds while retry delays use milliseconds.
 */
class BackendHttpSettingsTest {

    private static final String PREFIX = "DZP_FCS_BACKEND_";

    @Test
    void usesFiniteDefaultsWhenEnvironmentValuesAreAbsent() {
        BackendHttpSettings settings = BackendHttpSettings.from(Map.<String, String>of()::get);

        assertEquals(new BackendHttpSettings(Duration.ofSeconds(10), Duration.ofSeconds(50),
                Duration.ofSeconds(50), 3, 16, Duration.ofMillis(1000), Duration.ofMillis(10000)), settings);
    }

    @Test
    void readsEveryDocumentedEnvironmentOverrideInItsDeclaredUnit() {
        Map<String, String> environment = Map.of(
                PREFIX + "CONNECT_TIMEOUT_SECONDS", " 2 ",
                PREFIX + "READ_TIMEOUT_SECONDS", "3",
                PREFIX + "REQUEST_TIMEOUT_SECONDS", "4",
                PREFIX + "MAX_ATTEMPTS", "5",
                PREFIX + "MAX_CONCURRENT_REQUESTS", "6",
                PREFIX + "RETRY_DELAY_MILLIS", "7",
                PREFIX + "MAX_RETRY_DELAY_MILLIS", "8");

        BackendHttpSettings settings = BackendHttpSettings.from(environment::get);

        assertEquals(new BackendHttpSettings(Duration.ofSeconds(2), Duration.ofSeconds(3), Duration.ofSeconds(4),
                5, 6, Duration.ofMillis(7), Duration.ofMillis(8)), settings);
    }

    @Test
    void permitsDisabledBackoffWithoutDisablingRequestTimeouts() {
        Map<String, String> environment = Map.of(
                PREFIX + "RETRY_DELAY_MILLIS", "0",
                PREFIX + "MAX_RETRY_DELAY_MILLIS", "0");

        BackendHttpSettings settings = BackendHttpSettings.from(environment::get);

        assertEquals(Duration.ZERO, settings.retryDelay());
        assertEquals(Duration.ZERO, settings.maxRetryDelay());
        assertEquals(Duration.ofSeconds(50), settings.requestTimeout());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONNECT_TIMEOUT_SECONDS", "READ_TIMEOUT_SECONDS", "REQUEST_TIMEOUT_SECONDS",
        "MAX_ATTEMPTS", "MAX_CONCURRENT_REQUESTS"})
    void rejectsZeroAndNegativeLimitsWithTheirEnvironmentName(String suffix) {
        String name = PREFIX + suffix;
        for (String invalid : new String[] {"0", "-1"}) {
            assertInvalid(name, () -> BackendHttpSettings.from(Map.of(name, invalid)::get));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"RETRY_DELAY_MILLIS", "MAX_RETRY_DELAY_MILLIS"})
    void rejectsNegativeBackoffWithItsEnvironmentName(String suffix) {
        String name = PREFIX + suffix;

        assertInvalid(name, () -> BackendHttpSettings.from(Map.of(name, "-1")::get));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONNECT_TIMEOUT_SECONDS", "READ_TIMEOUT_SECONDS", "REQUEST_TIMEOUT_SECONDS",
        "MAX_ATTEMPTS", "MAX_CONCURRENT_REQUESTS", "RETRY_DELAY_MILLIS", "MAX_RETRY_DELAY_MILLIS"})
    void rejectsMalformedOrOutOfRangeNumbersWithTheirEnvironmentName(String suffix) {
        String name = PREFIX + suffix;
        for (String invalid : new String[] {"", " ", "not-a-number", "1.5", "9223372036854775808"}) {
            assertInvalid(name, () -> BackendHttpSettings.from(Map.of(name, invalid)::get));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONNECT_TIMEOUT_SECONDS", "READ_TIMEOUT_SECONDS", "REQUEST_TIMEOUT_SECONDS",
        "RETRY_DELAY_MILLIS", "MAX_RETRY_DELAY_MILLIS"})
    void rejectsDurationsThatCannotBeUsedForNanosecondDeadlines(String suffix) {
        String name = PREFIX + suffix;

        assertInvalid(name, () -> BackendHttpSettings.from(Map.of(name, Long.toString(Long.MAX_VALUE))::get));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONNECT_TIMEOUT_SECONDS", "READ_TIMEOUT_SECONDS"})
    void rejectsTimeoutsOutsideOkHttpMillisecondRange(String suffix) {
        String name = PREFIX + suffix;

        assertInvalid(name, () -> BackendHttpSettings.from(Map.of(name, "2147484")::get));
    }

    @ParameterizedTest
    @ValueSource(strings = {"MAX_ATTEMPTS", "MAX_CONCURRENT_REQUESTS"})
    void rejectsLimitsThatDoNotFitTheirIntegerRepresentation(String suffix) {
        String name = PREFIX + suffix;

        assertInvalid(name, () -> BackendHttpSettings.from(Map.of(name, "2147483648")::get));
    }

    @Test
    void identifiesBothSettingsWhenMaximumBackoffIsBelowInitialBackoff() {
        String maximumName = PREFIX + "MAX_RETRY_DELAY_MILLIS";
        String initialName = PREFIX + "RETRY_DELAY_MILLIS";

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> BackendHttpSettings.from(Map.of(maximumName, "999")::get));

        assertTrue(failure.getMessage().contains(maximumName));
        assertTrue(failure.getMessage().contains(initialName));
    }

    @Test
    void validatesDirectConstructionAsWellAsEnvironmentParsing() {
        assertInvalid(PREFIX + "CONNECT_TIMEOUT_SECONDS", () -> new BackendHttpSettings(null,
                Duration.ofSeconds(1), Duration.ofSeconds(1), 1, 1, Duration.ZERO, Duration.ZERO));
        assertInvalid(PREFIX + "READ_TIMEOUT_SECONDS", () -> new BackendHttpSettings(Duration.ofSeconds(1),
                Duration.ofNanos(1), Duration.ofSeconds(1), 1, 1, Duration.ZERO, Duration.ZERO));
        assertInvalid(PREFIX + "REQUEST_TIMEOUT_SECONDS", () -> new BackendHttpSettings(Duration.ofSeconds(1),
                Duration.ofSeconds(1), Duration.ZERO, 1, 1, Duration.ZERO, Duration.ZERO));
        assertInvalid(PREFIX + "RETRY_DELAY_MILLIS", () -> new BackendHttpSettings(Duration.ofSeconds(1),
                Duration.ofSeconds(1), Duration.ofSeconds(1), 1, 1, null, Duration.ZERO));
        assertInvalid(PREFIX + "MAX_RETRY_DELAY_MILLIS", () -> new BackendHttpSettings(Duration.ofSeconds(1),
                Duration.ofSeconds(1), Duration.ofSeconds(1), 1, 1, Duration.ZERO, null));
    }

    private static void assertInvalid(String name, Executable operation) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, operation);
        assertTrue(failure.getMessage().contains(name), failure.getMessage());
    }
}
