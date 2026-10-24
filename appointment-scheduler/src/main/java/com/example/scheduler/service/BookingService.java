package com.example.scheduler.service;

import com.example.scheduler.model.Appointment;
import com.example.scheduler.model.AppointmentStatus;
import com.example.scheduler.store.AppointmentStore;
import com.example.scheduler.util.UtcTimestamps;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * Conflict-safe booking and cancellation.
 *
 * <p>Invariant: for any provider, no two ACTIVE appointments overlap, where intervals are
 * half-open [start, end) so back-to-back appointments are allowed.
 *
 * <p>Enforcement: every read-check-write on a provider's calendar happens inside a
 * critical section guarded by a lock dedicated to that provider. Two concurrent bookings
 * for the same provider are therefore serialised; bookings for different providers never
 * contend. Validation runs before the lock is taken and before any state is touched, so
 * rejected input cannot change stored bookings.
 */
@Service
public final class BookingService {

    private final AppointmentStore store;
    private final Supplier<String> idGenerator;
    // One monitor per provider. Entries are never evicted (see docs/limitations).
    private final ConcurrentMap<String, Object> providerLocks = new ConcurrentHashMap<>();


    @Autowired
    public BookingService(AppointmentStore store) {
        this(store, () -> UUID.randomUUID().toString());
    }

    /** Visible for tests that need deterministic ids. */
    BookingService(AppointmentStore store, Supplier<String> idGenerator) {
        this.store = store;
        this.idGenerator = idGenerator;
    }

    public BookingResult book(String providerId, String clientId, String startUtc, String endUtc) {
        // ---- 1. Validate everything first. No state is read or written yet. ----
        final Instant start;
        final Instant end;
        try {
            requireId("providerId", providerId);
            requireId("clientId", clientId);
            start = UtcTimestamps.parse("start", startUtc);
            end = UtcTimestamps.parse("end", endUtc);
            if (!end.isAfter(start)) {
                throw new IllegalArgumentException("end must be after start (duration must be positive)");
            }
        } catch (IllegalArgumentException e) {
            return BookingResult.invalid(e.getMessage());
        }

        // ---- 2. Atomic check-and-insert, serialised per provider. ----
        synchronized (lockFor(providerId)) {
            for (Appointment existing : store.findByProvider(providerId)) {
                if (existing.isActive() && existing.overlaps(start, end)) {
                    return BookingResult.conflict(existing);
                }
            }
            Appointment created = new Appointment(
                    idGenerator.get(), providerId, clientId, start, end, AppointmentStatus.ACTIVE);
            store.save(created);
            return BookingResult.booked(created);
        }
    }

    public CancelResult cancel(String appointmentId) {
        if (appointmentId == null || appointmentId.isBlank()) {
            return CancelResult.invalid("appointmentId is required");
        }
        Optional<Appointment> found = store.findById(appointmentId);
        if (found.isEmpty()) {
            return CancelResult.notFound(appointmentId);
        }
        // An appointment's provider never changes, so it is safe to pick the lock from this read.
        // The state is re-read under the lock so two racing cancels resolve to one CANCELLED
        // and one ALREADY_CANCELLED.
        synchronized (lockFor(found.get().providerId())) {
            Appointment current = store.findById(appointmentId).orElseThrow();
            if (!current.isActive()) {
                return CancelResult.alreadyCancelled(current);
            }
            Appointment cancelled = current.cancelled();
            store.save(cancelled);
            return CancelResult.cancelled(cancelled);
        }
    }

    /** All appointments (any status), optionally for one provider, ordered by provider then start. */
    public List<Appointment> listAppointments(String providerIdOrNull) {
        List<Appointment> all = (providerIdOrNull == null || providerIdOrNull.isBlank())
                ? store.findAll()
                : store.findByProvider(providerIdOrNull);
        return all.stream()
                .sorted(Comparator.comparing(Appointment::providerId)
                        .thenComparing(Appointment::start)
                        .thenComparing(Appointment::id))
                .toList();
    }

    private static void requireId(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    private Object lockFor(String providerId) {
        return providerLocks.computeIfAbsent(providerId, k -> new Object());
    }
}
