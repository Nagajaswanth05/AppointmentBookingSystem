package com.example.scheduler.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Error body used for every non-2xx response produced by this API.
 */

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorResponse(
        String error,
        String message,
        String conflictingAppointmentId) {
}
