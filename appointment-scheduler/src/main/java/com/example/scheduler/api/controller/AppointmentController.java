package com.example.scheduler.api.controller;

import com.example.scheduler.api.dto.ApiErrorResponse;
import com.example.scheduler.api.dto.AppointmentResponse;
import com.example.scheduler.api.dto.BookAppointmentRequestDto;
import com.example.scheduler.service.BookingResult;
import com.example.scheduler.service.BookingService;
import com.example.scheduler.service.CancelResult;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin HTTP adapter over {@link BookingService}. It contains no business rules: it only maps
 * service results to HTTP status codes and JSON bodies.
 *
 * <pre>
 *   BOOKED -> 201      CANCELLED -> 200
 *   INVALID_INPUT -> 400
 *   NOT_FOUND -> 404
 *   CONFLICT / ALREADY_CANCELLED -> 409
 * </pre>
 */
@RestController
@RequestMapping(path = "/api/appointments", produces = MediaType.APPLICATION_JSON_VALUE)
public class AppointmentController {

    private final BookingService service;

    public AppointmentController(BookingService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> book(@RequestBody BookAppointmentRequestDto request) {
        BookingResult r = service.book(
                request.providerId(), request.clientId(), request.start(), request.end());
        return switch (r.status()) {
            case BOOKED -> ResponseEntity.status(HttpStatus.CREATED)
                    .body(AppointmentResponse.from(r.appointment()));
            case CONFLICT -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ApiErrorResponse("CONFLICT", r.message(), r.conflictingAppointmentId()));
            case INVALID_INPUT -> ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ApiErrorResponse("INVALID_INPUT", r.message(), null));
        };
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Object> cancel(@PathVariable("id") String id) {
        CancelResult r = service.cancel(id);
        return switch (r.status()) {
            case CANCELLED -> ResponseEntity.ok(AppointmentResponse.from(r.appointment()));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ApiErrorResponse("NOT_FOUND", r.message(), null));
            case ALREADY_CANCELLED -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ApiErrorResponse("ALREADY_CANCELLED", r.message(), null));
            case INVALID_INPUT -> ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ApiErrorResponse("INVALID_INPUT", r.message(), null));
        };
    }

    @GetMapping
    public List<AppointmentResponse> list(
            @RequestParam(name = "providerId", required = false) String providerId) {
        return service.listAppointments(providerId).stream().map(AppointmentResponse::from).toList();
    }
}
