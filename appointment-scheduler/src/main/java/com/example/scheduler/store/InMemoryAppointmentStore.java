package com.example.scheduler.store;

import com.example.scheduler.model.Appointment;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Thread-safe in-memory store: a primary index by id and a secondary index by provider. */
@Repository
public final class InMemoryAppointmentStore implements AppointmentStore {

    private final ConcurrentMap<String, Appointment> byId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Set<String>> idsByProvider = new ConcurrentHashMap<>();

    @Override
    public Optional<Appointment> findById(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public List<Appointment> findByProvider(String providerId) {
        Set<String> ids = idsByProvider.get(providerId);
        if (ids == null) {
            return List.of();
        }
        List<Appointment> result = new ArrayList<>();
        for (String id : ids) {
            Appointment a = byId.get(id);
            if (a != null) {
                result.add(a);
            }
        }
        return result;
    }

    @Override
    public List<Appointment> findAll() {
        return new ArrayList<>(byId.values());
    }

    @Override
    public void save(Appointment appointment) {
        // Write the primary record first, then index it, so readers never find an id
        // in the index that is missing from byId.
        byId.put(appointment.id(), appointment);
        idsByProvider
                .computeIfAbsent(appointment.providerId(), k -> ConcurrentHashMap.newKeySet())
                .add(appointment.id());
    }
}
