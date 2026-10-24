package com.example.scheduler.api.dto;

import com.example.scheduler.model.Appointment;
/** JSON view of an appointment. Timestamps are ISO-8601 UTC strings (e.g. 2026-03-02T09:00:00Z). */

public record AppointmentResponse(
         String id,
         String providerId,
         String clientId,
         String start,
         String end,
         String status) {

    public static AppointmentResponse from(Appointment a) {
        return new AppointmentResponse(
                a.id(), a.providerId(), a.clientId(),
                a.start().toString(), a.end().toString(), a.status().name());
    }
}
