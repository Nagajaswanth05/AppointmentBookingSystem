package com.example.scheduler.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable appointment. The time range is half-open: [start, end).
 * All instants are UTC by construction (java.time.Instant has no zone).
 */
public record Appointment(
        String id,
        String providerId,
        String clientId,
        Instant start,
        Instant end,
        AppointmentStatus status) {

    public Appointment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(providerId, "providerId");
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        Objects.requireNonNull(status, "status");
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("end must be after start");
        }
    }

    public boolean isActive() {
        return status == AppointmentStatus.ACTIVE;
    }

    /**
     * Half-open interval overlap: [start, end) intersects [otherStart, otherEnd)
     * iff start < otherEnd AND otherStart < end. Touching endpoints do NOT overlap.
     */
    public boolean overlaps(Instant otherStart, Instant otherEnd) {
        return start.isBefore(otherEnd) && otherStart.isBefore(end);
    }

    public Appointment cancelled() {
        return new Appointment(id, providerId, clientId, start, end, AppointmentStatus.CANCELLED);
    }
}
