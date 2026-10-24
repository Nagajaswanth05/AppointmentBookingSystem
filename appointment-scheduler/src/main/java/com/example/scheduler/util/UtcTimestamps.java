package com.example.scheduler.util;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Strict UTC timestamp parsing. Accepted: ISO-8601 instants ending in an upper-case 'Z',
 * e.g. 2026-03-02T09:00:00Z. Rejected: offsets (+05:30), missing zone, lower-case 'z',
 * date-only values, and anything unparseable. Rejecting offsets (instead of silently
 * converting them) keeps the "UTC only" assumption explicit.
 */
public final class UtcTimestamps {

    private UtcTimestamps() { }

    /** @throws IllegalArgumentException with a user-facing message if the value is not valid. */
    public static Instant parse(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (!value.endsWith("Z")) {
            throw new IllegalArgumentException(field + " must be a UTC ISO-8601 timestamp ending in 'Z', e.g. 2026-03-02T09:00:00Z");
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field + " is not a valid UTC ISO-8601 timestamp: '" + value + "'");
        }
    }
}
