# Design Specification: Conflict-Safe Booking & Cancellation

Scope: one bounded slice of a scheduling service. Out of scope per the brief: frontend, auth,
notifications, recurring availability, calendar integration, deployment, new databases.

## 1. Service boundary

`BookingService` is the only component that may create or cancel appointments, and the only
place the invariant is enforced.

```
    AppointmentSchedulerApplication (Spring Boot component scanning)
                     │
        AppointmentController (REST)
                     │
               BookingService
                     │
        AppointmentStore (interface)
                     │
        InMemoryAppointmentStore
```

- **Inbound:** `book(providerId, clientId, startUtc, endUtc)`, `cancel(appointmentId)`,
  `listAppointments(providerIdOrNull)` (read-only helper).
- **Outbound:** `AppointmentStore` interface (`findById`, `findByProvider`, `findAll`, `save`).
  The store is deliberately dumb: it is thread-safe but does not enforce the invariant.
- `AppointmentController` is a thin adapter and holds no booking rules; it delegates to
  `BookingService`.
- `BookingService` and `InMemoryAppointmentStore` are Spring components. Component scanning
  creates one singleton of each, and constructor injection supplies the store to the service.
  There is no separate `BookingConfig` class.
- The service and store use Spring annotations, so this implementation is not framework-free.
  The model and overlap logic remain plain Java.

## 2. Data design

`Appointment` (immutable record):

| Field | Type | Notes |
|---|---|---|
| `id` | String | Server-generated UUID; never supplied by callers |
| `providerId` | String | Required, non-blank, compared exactly (case-sensitive, untrimmed) |
| `clientId` | String | Required, non-blank; not used for conflict decisions |
| `start`, `end` | `Instant` | UTC. Half-open interval `[start, end)`; `end > start` enforced in the constructor too |
| `status` | `ACTIVE` \| `CANCELLED` | Cancelled records are retained, not deleted |

Cancellation is a **soft state change** (new immutable copy with `CANCELLED` saved over the
old one). This keeps history, makes `ALREADY_CANCELLED` distinguishable from `NOT_FOUND`, and
means a cancelled appointment's id can never be silently reused.

## 3. API / result contract

Inputs are strings so that "invalid timestamp" is a *service-level* validation outcome rather
than a caller-side parse error.

`book(...)` → `BookingResult { status, appointment?, conflictingAppointmentId?, message }`

| Status | When |
|---|---|
| `BOOKED` | Valid input and no active overlap for that provider |
| `CONFLICT` | An active appointment of the same provider overlaps; returns that appointment's id |
| `INVALID_INPUT` | Missing/blank provider or client, bad/missing/non-UTC timestamp, `end <= start` |

`cancel(id)` → `CancelResult { status, appointment?, message }`

| Status | When |
|---|---|
| `CANCELLED` | Was `ACTIVE`; now `CANCELLED` |
| `NOT_FOUND` | No appointment with that id |
| `ALREADY_CANCELLED` | Exists but already cancelled (state unchanged) |
| `INVALID_INPUT` | Missing/blank id |

Expected outcomes are values, not exceptions. Exceptions are reserved for programmer error.

### HTTP mapping (REST adapter)

The adapter only translates results to HTTP; it adds no rules.

| Endpoint | Service result | HTTP |
|---|---|---|
| `POST /api/appointments` | `BOOKED` / `CONFLICT` / `INVALID_INPUT` | 201 / 409 / 400 |
| `POST /api/appointments/{id}/cancel` | `CANCELLED` / `NOT_FOUND` / `ALREADY_CANCELLED` / `INVALID_INPUT` | 200 / 404 / 409 / 400 |
| `GET /api/appointments[?providerId=]` | list | 200 |

- Cancel is `POST .../cancel`, not `DELETE`, because the appointment is retained with status `CANCELLED`.
- Request DTO fields are strings so "invalid timestamp" is reported by the service (400 `INVALID_INPUT`),
  not rejected earlier by the JSON layer in a different format. Response timestamps are ISO-8601 UTC strings.
- All errors share one body: `{ "error", "message", "conflictingAppointmentId"? }`. Malformed or missing JSON
  is mapped to the same shape by `ApiExceptionHandler`. A wrong `Content-Type` yields Spring's standard 415.
- Springdoc provides Swagger UI for the REST endpoints; curl or Postman can also be used.

### Timestamp format
ISO-8601 instant ending in upper-case `Z` (e.g. `2026-03-02T09:00:00Z`). Offsets such as
`+05:30` and `+00:00`, zone-less strings, lower-case `z`, date-only values and epoch numbers
are **rejected, not converted**. This keeps "UTC only" explicit and avoids silent shifts.

## 4. The booking invariant

> For every provider P, for every pair of distinct appointments A, B with
> `A.providerId = B.providerId = P`, if both are `ACTIVE` then
> `NOT (A.start < B.end AND B.start < A.end)`.

Half-open intervals mean `A.end == B.start` is **not** an overlap (adjacency allowed).
Appointments of different providers never interact. A `CANCELLED` appointment is ignored by
the check, so it no longer blocks its time.

## 5. Concurrency approach

The risk is a check-then-act race: two requests for the same provider both read "no
conflict", then both insert.

- `BookingService` keeps a `ConcurrentHashMap<providerId, Object>` of monitors (`lockFor`).
- The lock map is owned by that `BookingService` instance and entries are never evicted. It grows with each distinct provider ID seen during the instance's lifetime.
- `book` runs *validate (no state touched)* → `synchronized(lockFor(provider)) { scan the
  provider's appointments; if none active-overlap, create + save }`.
- `cancel` finds the appointment's provider, takes the same lock, **re-reads** the record under
  the lock, then flips the state. The re-read makes racing cancels resolve to exactly one
  `CANCELLED` and the rest `ALREADY_CANCELLED`, and makes book-vs-cancel interleavings safe.
- Different providers use different monitors, so they never block each other.
- Appointment ids are server-generated UUIDs, so there is no global uniqueness check that
  would need a cross-provider lock.

Why this and not a global lock? A single lock is simpler but serialises unrelated providers.
Why not optimistic retry on the store? With an in-memory map there is no natural version
check; the lock is simpler and clearly correct.

The invariant is only guaranteed if **all** writes go through the same `BookingService`
instance. A second service instance—even in the same JVM—has a separate lock map, and separate
processes do not coordinate. See the limitations document; this in-memory locking strategy is
for the assessment prototype, not production deployment.

## 6. Persistence approach

Per the brief: no starter, so an in-memory store behind an interface. `InMemoryAppointmentStore`
holds `byId` and `idsByProvider` in concurrent maps; `save` writes the primary record first,
then the index, so a reader never finds an indexed id with no record.

**Migration path to a database** (not implemented): implement `AppointmentStore` with SQL,
and replace the monitor with a transaction that locks the provider row
(`SELECT ... FOR UPDATE`) or a PostgreSQL exclusion constraint
(`EXCLUDE USING gist (provider_id WITH =, tstzrange(start_ts, end_ts, '[)') WITH &&) WHERE (status='ACTIVE')`).
The constraint-based approach also protects against multiple service instances, which a JVM
lock cannot.

## 7. Key assumptions & trade-offs

| Decision | Trade-off |
|---|---|
| Per-provider `synchronized`, in-memory | Simple for one service instance; does not coordinate separate instances/processes, and lock entries are retained for that instance's lifetime |
| Overlap check scans the provider's appointments (O(n) per booking) | Fine for a prototype; a sorted structure/interval tree or DB index is the upgrade |
| Soft-cancel (keep records) | Memory grows; preserves history and clear cancel semantics |
| Reject non-`Z` timestamps rather than convert | Stricter than necessary; avoids silent timezone bugs |
| Server-generated ids | Callers can't do idempotent retries with their own key (see limitations) |
| Results instead of exceptions | Callers must check `status()`; avoids exception-driven control flow |
| Spring Boot REST API with Swagger UI | Adds framework and API surface; the booking invariant remains in `BookingService` and is covered by direct service tests |
| Bookings in the past are allowed | No clock dependency, deterministic tests |
