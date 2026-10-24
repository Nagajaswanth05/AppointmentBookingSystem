package com.example.scheduler.store;

import com.example.scheduler.model.Appointment;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Persistence port. Implementations must be safe for concurrent use, but they do NOT
 * enforce the no-overlap invariant: BookingService owns that, via its per-provider lock.
 * Swapping in a database later means implementing this interface (and replacing the
 * lock with a transaction/constraint; see docs/design-spec.md).
 */
@Repository
public interface AppointmentStore {

    Optional<Appointment> findById(String id);

    /** All appointments (any status) for a provider. Snapshot; order unspecified. */
    List<Appointment> findByProvider(String providerId);

    /** All appointments (any status). Snapshot; order unspecified. */
    List<Appointment> findAll();

    /** Insert or replace by id. */
    void save(Appointment appointment);
}
