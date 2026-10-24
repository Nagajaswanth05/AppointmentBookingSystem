package com.example.scheduler.model;

/** Lifecycle state of an appointment. Only ACTIVE appointments block a provider's time. */
public enum AppointmentStatus {
    ACTIVE,
    CANCELLED
}
