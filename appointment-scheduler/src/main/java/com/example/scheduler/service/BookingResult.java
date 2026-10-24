package com.example.scheduler.service;

import com.example.scheduler.model.Appointment;

/** Outcome of a booking attempt. Expected failures are results, not exceptions. */
public record BookingResult(
        Status status,
        Appointment appointment,
        String conflictingAppointmentId,
        String message) {

    public enum Status { BOOKED, CONFLICT, INVALID_INPUT }

    public boolean isBooked() {
        return status == Status.BOOKED;
    }

    static BookingResult booked(Appointment a) {
        return new BookingResult(Status.BOOKED, a, null, "Appointment booked.");
    }

    static BookingResult conflict(Appointment existing) {
        return new BookingResult(Status.CONFLICT, null, existing.id(),
                "Provider already has an active appointment overlapping that time (" + existing.id() + ").");
    }

    static BookingResult invalid(String reason) {
        return new BookingResult(Status.INVALID_INPUT, null, null, reason);
    }
}
