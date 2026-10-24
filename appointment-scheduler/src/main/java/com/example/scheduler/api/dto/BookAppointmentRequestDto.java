package com.example.scheduler.api.dto;
/**
 * Request body for booking. All fields are plain strings on purpose: validation (missing ids,
 * non-UTC or unparseable timestamps, non-positive duration) is done in BookingService.
 */

public record BookAppointmentRequestDto(
         String providerId,
         String clientId,
         String start,
         String end) {
}
