package com.example.scheduler.service;

import com.example.scheduler.model.Appointment;

/** Outcome of a cancellation attempt. */
public record CancelResult(Status status, Appointment appointment, String message) {

    public enum Status { CANCELLED, NOT_FOUND, ALREADY_CANCELLED, INVALID_INPUT }

    public boolean isCancelled() {
        return status == Status.CANCELLED;
    }

    static CancelResult cancelled(Appointment a) {
        return new CancelResult(Status.CANCELLED, a, "Appointment cancelled.");
    }

    static CancelResult notFound(String id) {
        return new CancelResult(Status.NOT_FOUND, null, "No appointment with id '" + id + "'.");
    }

    static CancelResult alreadyCancelled(Appointment a) {
        return new CancelResult(Status.ALREADY_CANCELLED, a, "Appointment '" + a.id() + "' was already cancelled.");
    }

    static CancelResult invalid(String reason) {
        return new CancelResult(Status.INVALID_INPUT, null, reason);
    }
}
