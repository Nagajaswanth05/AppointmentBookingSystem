# Appointment Scheduler: conflict-safe booking & cancellation

A bounded Spring Boot REST service for **booking** and **cancelling** provider appointments,
with an in-memory store and UTC-only timestamps. There is no CLI or frontend.
This is an assessment prototype, not production-ready: data is lost on restart, and booking
locks coordinate only requests handled by the same service instance.

> No starter project was supplied with the assessment, so this uses the fallback described in
> the brief: a small service over an in-memory store.

## Booking invariant (one line)
For any provider, no two `ACTIVE` appointments overlap. Intervals are half-open `[start, end)`,
so an appointment ending at 10:00 and another starting at 10:00 do **not** conflict.

## Prerequisites
- JDK 17 or newer (`java -version`; `mvn -v` must also report 17+)
- Maven 3.8+ (downloads Spring Boot 3.2.5 and springdoc 2.5.0 on first run)

## Run and test

```bash
mvn clean test          # run all tests, including Spring context and REST tests
mvn spring-boot:run     # start the REST API on http://localhost:8080
```

REST endpoints and curl examples: **[docs/api-testing-guide.md](docs/api-testing-guide.md)**.
Interactive API documentation is available in Swagger UI at
**http://localhost:8080/swagger-ui/index.html** after starting the application.

## REST API at a glance

| Method & path | Purpose | Status codes |
|---|---|---|
| `POST /api/appointments` | Book (JSON body) | 201, 400 invalid, 409 overlap |
| `POST /api/appointments/{id}/cancel` | Cancel | 200, 404 unknown, 409 already cancelled |
| `GET /api/appointments[?providerId=]` | List | 200 |

All responses are JSON; errors look like `{"error":"CONFLICT","message":"...","conflictingAppointmentId":"..."}`.

## Using the service from code

```java
BookingService service = new BookingService(new InMemoryAppointmentStore());
BookingResult r = service.book("dr-a", "pat-1", "2026-03-02T09:00:00Z", "2026-03-02T09:30:00Z");
// r.status(): BOOKED | CONFLICT | INVALID_INPUT
CancelResult c = service.cancel(r.appointment().id());
// c.status(): CANCELLED | NOT_FOUND | ALREADY_CANCELLED | INVALID_INPUT
```

Expected failures (conflict, bad input, missing/duplicate cancel) are returned as result
values, not thrown, so callers must inspect `status()`.

`BookingService` contains the booking rules and is registered as a Spring service. Spring
component scanning injects the `InMemoryAppointmentStore` implementation of `AppointmentStore`.

## Layout

```
src/main/java/com/example/scheduler/
  model/    Appointment (immutable record), AppointmentStatus
  store/    AppointmentStore (port), InMemoryAppointmentStore
  service/  BookingService (the invariant lives here), BookingResult, CancelResult
  util/     UtcTimestamps (strict UTC timestamp parsing)
  api/      Spring Boot REST controller, DTOs, error handler
  AppointmentSchedulerApplication.java
src/main/resources/application.properties
src/test/java/...   unit, randomized invariant, concurrency, Spring context, and REST tests
docs/
  api-testing-guide.md           run + test the REST API with curl
  design-spec.md                 service boundary, data/API design, invariant, concurrency, tradeoffs
  assumptions-and-limitations.md assumptions note + limitations / follow-up risks
  ai-usage-record.md             prompts, responses, accept/edit/reject decisions, verification
```

## Test coverage (maps to acceptance criteria)

| Criterion | Where tested |
|---|---|
| 1. Valid booking | `BookingServiceTest.SuccessfulBooking` |
| 2. Same-provider overlap rejected; different providers fine | `SameProviderOverlap` (7 overlap shapes), `DifferentProviders` |
| 3. Adjacency allowed | `Adjacency` (both sides, and exact gap-filling) |
| 4. Cancellation frees the slot; missing / double cancel is clear | `Cancellation` |
| 5. Invalid input rejected, store unchanged | `InvalidInput` (store snapshot compared before/after) |
| 6. Concurrency | `BookingConcurrencyTest` (32 threads, start gate, repeated) |
| REST mapping of all of the above | `AppointmentControllerTest` (status codes + JSON for book, cancel, list, errors) |
| Spring wiring | `ApplicationContextTest` starts the real Spring context and exercises the appointment endpoint |
| Extra | `RandomizedInvariantTest` compares the service with an independent slot-bitmap model over 20,000 random operations |

See `docs/ai-usage-record.md` for verification evidence, including mutation checks.
