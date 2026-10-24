# AI Prompt and Use Record

## Scope

This record documents AI-assisted engineering for a conflict-safe appointment booking and cancellation service using an in-memory store. The final project exposes the service through a Spring Boot REST API with Swagger UI as an optional testing adapter.

Each entry records: the prompt given, AI response summary, decision rationale, affected files, and verification evidence.

---

## Prompt Log

### 1. Initial Architecture and Decomposition

**AI Tool:** Claude (Anthropic)

**Prompt:**
```
Context: I have an assessment requiring a bounded appointment-booking feature that must 
prevent overlapping active appointments for a provider. No starter project was supplied.

Task: Review the assessment requirements and recommend:
1. Project structure and initial setup
2. Core data model design
3. Implementation sequence
4. Test strategy mapped to acceptance criteria

Constraints: 
- Use in-memory storage (no database)
- UTC timestamps only
- Minimal scope—no frontend, auth, or notifications

Questions:
- Should I use half-open intervals [start, end) or closed intervals [start, end]?
- What's the simplest concurrency approach for a single-JVM prototype?
```

**AI Response Summary:**
- Recommended half-open intervals `[start, end)` where `start < otherEnd && otherStart < end` detects overlap
- Suggested UTC `Instant` for timestamps with strict ISO-8601 parsing
- Proposed validate-then-mutate pattern: check all preconditions before modifying state
- Recommended per-provider locking using `ConcurrentHashMap` to allow parallel bookings for different providers
- Suggested test categories: booking success, overlap rejection, adjacency allowed, cross-provider isolation, cancellation, invalid input

**Decision:** **Accepted with verification**

| Suggestion | Decision | Reasoning |
|------------|----------|-----------|
| Half-open intervals | ✓ Accepted | Tested manually: `[09:00, 10:00)` and `[10:00, 11:00)` → `9 < 11 && 10 < 10` = false. Adjacent slots correctly allowed. |
| UTC Instant | ✓ Accepted | Matches assessment requirement; avoids timezone ambiguity |
| Validate-before-mutate | ✓ Accepted | Ensures invalid input never modifies store (acceptance criterion 5) |
| Per-provider locking | ✓ Accepted | More granular than global lock; different providers don't block each other |
| CLI interface | ✗ Rejected | Later replaced with REST API for better testability |

**Affected Files:** Project structure, `BookingService.java`, `AppointmentStore.java`, test organization

**Verification:**
- Wrote adjacency test before implementation: two appointments `[10:00, 11:00)` and `[11:00, 12:00)` for same provider → both booked successfully
- Overlap test: `[10:00, 11:00)` and `[10:30, 11:30)` → second rejected with `CONFLICT` status

---

### 2. Overlap Detection Logic Review

**AI Tool:** Claude (Anthropic)

**Prompt:**
```
Context: I'm implementing overlap detection for appointment booking.

My current predicate:
existingAppts.stream()
    .filter(Appointment::isActive)
    .anyMatch(existing -> 
        newStart.isBefore(existing.getEndTime()) && 
        existing.getStartTime().isBefore(newEnd))

Task: Verify this handles these edge cases correctly:
1. Adjacent: A=[09:00-10:00], B=[10:00-11:00] → should ALLOW
2. Partial overlap: A=[09:00-10:30], B=[10:00-11:00] → should REJECT  
3. Complete containment: A=[09:00-12:00], B=[10:00-11:00] → should REJECT
4. Identical times: A=[10:00-11:00], B=[10:00-11:00] → should REJECT
5. Cancelled appointment: A=[10:00-11:00] cancelled, B=[10:00-11:00] → should ALLOW

Questions:
- Is there a TOCTOU (time-of-check-time-of-use) race condition here?
- Should the filter happen inside or outside the lock?
```

**AI Response Summary:**
- Confirmed predicate is correct for cases 1-4
- Noted that `filter(Appointment::isActive)` handles case 5 correctly
- **Identified TOCTOU risk:** If check and insert aren't atomic, two threads could both pass the overlap check and both insert
- Recommended: acquire per-provider lock *before* the overlap check, hold it through insertion

**Decision:** **Accepted the TOCTOU fix**

The AI correctly identified a concurrency bug I hadn't considered. Without locking around both check and insert:
```
Thread 1: check overlap → none found
Thread 2: check overlap → none found  
Thread 1: insert appointment A
Thread 2: insert appointment A' (OVERLAPS! but check already passed)
```

**My Implementation:**
```java
public BookingResult book(String providerId, Instant start, Instant end) {
    // Validation first (no lock needed)
    if (providerId == null || providerId.isBlank()) {
        return BookingResult.invalid("Provider ID is required");
    }
    // ... other validation ...

    // Lock per-provider for check-then-insert atomicity
    synchronized (getLockForProvider(providerId)) {
        List<Appointment> existing = store.findActiveByProvider(providerId);
        
        boolean hasConflict = existing.stream()
            .anyMatch(appt -> overlaps(appt, start, end));
        
        if (hasConflict) {
            return BookingResult.conflict("Time slot conflicts with existing appointment");
        }
        
        Appointment appointment = new Appointment(
            UUID.randomUUID().toString(), providerId, start, end, Status.ACTIVE
        );
        store.save(appointment);
        return BookingResult.success(appointment);
    }
}
```

**Affected Files:** `BookingService.java`, `BookingServiceConcurrencyTest.java`

**Verification:**
```
Concurrency test: 100 threads simultaneously booking [10:00-11:00] for same provider
Expected: Exactly 1 success, 99 conflicts
Actual: 1 success, 99 conflicts ✓

Different providers test: 100 threads booking same time for 100 different providers  
Expected: 100 successes (no cross-provider blocking)
Actual: 100 successes ✓
```

---

### 3. Initial Code Generation

**AI Tool:** Claude (Anthropic)

**Prompt:**
```
Context: Implementing the booking service based on our agreed design.

Task: Generate initial implementation for:
1. Appointment model with id, providerId, start, end, status
2. AppointmentStore with in-memory storage
3. BookingService with book() and cancel() methods
4. Result types for booking and cancellation outcomes

Constraints:
- Immutable Appointment model
- Status enum: ACTIVE, CANCELLED
- Return result objects, not exceptions, for expected outcomes
- Throw exceptions only for programming errors
```

**AI Response Summary:**
Generated complete initial implementation including model, store, service, and result types.

**Decision:** **Accepted with edits**

| Component | Decision | Changes Made |
|-----------|----------|--------------|
| Appointment model | ✓ Accepted | None needed |
| AppointmentStore | ✓ Accepted | None needed |
| BookingService.book() | ✓ Accepted | None needed |
| BookingService.cancel() | **Edited** | Fixed constructor argument error (see below) |
| BookingResult | ✓ Accepted | None needed |
| CancelResult | **Edited** | Fixed factory method signature |

**Bug Found in AI-Generated Code:**

```java
// AI generated (INCORRECT):
public CancelResult cancel(String appointmentId) {
    // ...
    if (appointment == null) {
        return CancelResult.invalid(appointmentId, "Appointment not found");
        //                          ^^^^^^^^^^^^^ Extra argument!
    }
}

// CancelResult.invalid() signature only takes reason:
public static CancelResult invalid(String reason) { ... }

// My fix:
if (appointment == null) {
    return CancelResult.invalid("Appointment not found: " + appointmentId);
}
```

**Why this matters:** This demonstrates that AI-generated code requires careful review. The AI was inconsistent between the factory method definition and its usage.

**Affected Files:** `Appointment.java`, `AppointmentStore.java`, `BookingService.java`, `BookingResult.java`, `CancelResult.java`

**Verification:**
- Compilation succeeded after fix
- Unit test for cancelling non-existent appointment:
  ```java
  @Test
  void cancel_nonExistentAppointment_returnsNotFound() {
      CancelResult result = service.cancel("non-existent-id");
      assertEquals(CancelResult.Status.NOT_FOUND, result.getStatus());
      assertTrue(result.getReason().contains("non-existent-id"));
  }
  ```
  Test passed ✓

---

### 4. Maven Compiler Configuration Error

**AI Tool:** Claude (Anthropic)

**Prompt:**
```
Context: Maven build fails with this error:

[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.1:compile
Fatal error compiling: error: invalid source release: 5

Task: Explain the cause and provide the correct Maven configuration for Java 17 
with Spring Boot 3.x

Current pom.xml has no explicit Java version configured.
```

**AI Response Summary:**
- Explained that Maven defaults to Java 5 source/target when not specified
- Spring Boot 3.x requires Java 17 minimum
- Recommended using Spring Boot parent POM which sets sensible defaults
- Provided configuration snippet

**Decision:** **Accepted and applied**

```xml
<!-- Added to pom.xml -->
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.2.0</version>
</parent>

<properties>
    <java.version>17</java.version>
</properties>
```

**Affected Files:** `pom.xml`

**Verification:**
```bash
$ mvn clean compile
[INFO] BUILD SUCCESS

$ mvn -version
Apache Maven 3.9.x
Java version: 17.0.x
```

---

### 5. REST API Design

**AI Tool:** Claude (Anthropic)

**Prompt:**
```
Context: The core booking service is complete with passing tests. Currently no 
external interface exists.

Task: Assess whether adding a REST API is proportionate for this assessment, and 
if so, design endpoints for:
1. Book an appointment
2. Cancel an appointment by ID
3. List all appointments (for verification)

Constraints:
- Thin controller layer—delegate to existing BookingService
- Return appropriate HTTP status codes
- Include Swagger/OpenAPI for easy testing
- Don't add features beyond the assessment scope

Trade-off question: Should I use ResponseEntity everywhere or rely on 
@ResponseStatus annotations?
```

**AI Response Summary:**
- Confirmed REST API is proportionate as it provides a testable interface without adding scope
- Proposed endpoints:
    - `POST /api/appointments` → 201 Created or 409 Conflict
    - `DELETE /api/appointments/{id}` → 200 OK or 404 Not Found
    - `GET /api/appointments` → 200 OK with list
- Recommended `ResponseEntity` for explicit control over status codes and headers
- Suggested DTO classes to decouple API contract from internal model

**Decision:** **Accepted with modifications**

| Suggestion | Decision | Reasoning |
|------------|----------|-----------|
| REST endpoints | ✓ Accepted | Provides testable interface; Swagger UI aids manual verification |
| ResponseEntity | ✓ Accepted | Explicit status codes clearer than annotations |
| Separate DTOs | ✓ Accepted | Decouples API from internal model; allows API evolution |
| HATEOAS links | ✗ Rejected | Over-engineering for this scope |
| Pagination | ✗ Rejected | Not needed for prototype with small data set |

**Affected Files:** `AppointmentController.java`, `BookingRequest.java`, `BookingResponse.java`, `ApiExceptionHandler.java`, `pom.xml` (added springdoc-openapi)

**Verification:**
```java
// MockMvc test for conflict scenario
@Test
void bookAppointment_conflict_returns409() throws Exception {
    // First booking succeeds
    mockMvc.perform(post("/api/appointments")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"providerId": "dr-smith", 
                 "startTime": "2024-01-15T10:00:00Z",
                 "endTime": "2024-01-15T11:00:00Z"}
                """))
            .andExpect(status().isCreated());
    
    // Overlapping booking returns 409
    mockMvc.perform(post("/api/appointments")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"providerId": "dr-smith",
                 "startTime": "2024-01-15T10:30:00Z", 
                 "endTime": "2024-01-15T11:30:00Z"}
                """))
            .andExpect(status().isConflict());
}
```
Test passed ✓

Runtime smoke test:
```bash
$ curl -X GET http://localhost:8080/api/appointments
[]
$ # Empty array returned, HTTP 200 ✓
```

---

### 6. Import Resolution Error

**AI Tool:** Claude (Anthropic)

**Prompt:**
```
Context: IDE shows unresolved imports in ApiExceptionHandler.java:

import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.http.HttpStatus;  // <-- Red underline

The HttpStatus import specifically shows "Cannot resolve symbol"

Task: Is this a code error or a dependency/IDE issue? How do I diagnose?
```

**AI Response Summary:**
- Identified that `HttpStatus` is in `org.springframework.http` package (correct)
- This package is in `spring-web` dependency, which should be included via `spring-boot-starter-web`
- Likely cause: Maven dependencies not yet downloaded/indexed by IDE
- Recommended: `mvn dependency:resolve` then IDE refresh/reimport

**Decision:** **Accepted diagnosis**

The import statement was correct. Issue was that Maven dependencies hadn't been downloaded after adding Spring Boot starter.

**Resolution Steps:**
```bash
$ mvn dependency:resolve
[INFO] BUILD SUCCESS

# In IntelliJ: Right-click pom.xml → Maven → Reload Project
```

**Affected Files:** None (IDE configuration only)

**Verification:** All imports resolved, compilation succeeded

---

### 7. Spring Context Startup Failure

**AI Tool:** GitHub Copilot

**Prompt:**
```
Context: Spring Boot application fails to start with:

org.springframework.beans.BeanInstantiationException: 
Failed to instantiate [BookingService]: No default constructor found
Caused by: java.lang.NoSuchMethodException: BookingService.<init>()

BookingService doesn't have this constructor:
public BookingService(AppointmentStore store) {
    this.store = store;
}

AppointmentStore is annotated with @Repository.

Task: Why isn't Spring using the constructor that takes AppointmentStore?
```

**AI Response Summary:**
- Spring needs to know which constructor to use for dependency injection
- With single constructor, Spring 4.3+ auto-detects it, but something may be interfering
- Also verify `AppointmentStore` is being component-scanned

**Decision:** **Accepted and applied**

```java
@Service
public class BookingService {
    
    private final AppointmentStore store;
    
     // Added explicitly
    public BookingService(AppointmentStore store) {
        this.store = store;
    }
    // ...
}
```

**Root Cause Analysis:** The `@Service` annotation was present but Spring wasn't finding the constructor. Adding explicit Constructor resolved the ambiguity.

**Affected Files:** `BookingService.java`

**Verification:**
```java
@SpringBootTest
class ApplicationContextTest {
    
    @Autowired
    private ApplicationContext context;
    
    @Test
    void contextLoads() {
        assertNotNull(context.getBean(BookingService.class));
        assertNotNull(context.getBean(AppointmentStore.class));
        assertNotNull(context.getBean(AppointmentController.class));
    }
}
```
Test passed ✓

---

### 8. Test Coverage Review

**AI Tool:** Claude (Anthropic)

**Prompt:**
```
Context: I have tests for the main booking scenarios. Here's my current test list:

Service tests:
- book_validAppointment_succeeds
- book_overlappingAppointment_returnsConflict
- book_adjacentAppointments_bothSucceed
- book_differentProviders_noConflict
- cancel_activeAppointment_succeeds
- cancel_alreadyCancelled_returnsAlreadyCancelled
- cancel_nonExistent_returnsNotFound

Task: Review against acceptance criteria and identify gaps.

Acceptance criteria:
1. Valid booking for provider with positive duration
2. Overlap rejected for same provider
3. Adjacent allowed
4. Cancelled slot can be rebooked
5. Invalid input rejected without changing state
6. Concurrency test if applicable
```

**AI Response Summary:**
Identified gaps:
1. ✗ Missing: Rebooking a cancelled slot (criterion 4)
2. ✗ Missing: Invalid input tests (criterion 5)
    - Null/blank provider ID
    - Null timestamps
    - End before start (non-positive duration)
    - End equals start (zero duration)
3. ✗ Missing: Concurrency test (criterion 6)
4. ✓ Covered: Criteria 1, 2, 3

**Decision:** **Accepted and implemented missing tests**

```java
// Added: Rebooking cancelled slot
@Test
void book_afterCancellation_succeeds() {
    BookingResult first = service.book("dr-smith", 
        parse("2024-01-15T10:00:00Z"), 
        parse("2024-01-15T11:00:00Z"));
    
    service.cancel(first.getAppointment().getId());
    
    BookingResult second = service.book("dr-smith",
        parse("2024-01-15T10:00:00Z"),
        parse("2024-01-15T11:00:00Z"));
    
    assertEquals(BookingResult.Status.SUCCESS, second.getStatus());
}

// Added: Invalid input tests
@Test
void book_nullProviderId_returnsInvalid() {
    BookingResult result = service.book(null,
        parse("2024-01-15T10:00:00Z"),
        parse("2024-01-15T11:00:00Z"));
    
    assertEquals(BookingResult.Status.INVALID, result.getStatus());
    assertTrue(store.findAll().isEmpty()); // State unchanged
}

@Test
void book_endBeforeStart_returnsInvalid() {
    BookingResult result = service.book("dr-smith",
        parse("2024-01-15T11:00:00Z"),  // start
        parse("2024-01-15T10:00:00Z")); // end before start!
    
    assertEquals(BookingResult.Status.INVALID, result.getStatus());
}

@Test
void book_zeroDuration_returnsInvalid() {
    Instant time = parse("2024-01-15T10:00:00Z");
    BookingResult result = service.book("dr-smith", time, time);
    
    assertEquals(BookingResult.Status.INVALID, result.getStatus());
}
```

**Affected Files:** `BookingServiceTest.java`, `BookingServiceConcurrencyTest.java`, `BookingServiceInvariantTest.java`

**Verification:**
```
$ mvn test
[INFO] Tests run: 160, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

---

### 9. Randomized Invariant Testing Approach

**AI Tool:** Claude (Anthropic)

**Prompt:**
```
Context: I want to add property-based/invariant testing to increase confidence in 
the overlap logic beyond my hand-crafted examples.

Task: Suggest an approach for randomized testing that verifies:
"After any sequence of book/cancel operations, no two ACTIVE appointments for the 
same provider overlap"

Constraints:
- Don't add heavy dependencies (no jqwik or QuickCheck)
- Keep it simple—just use Random with JUnit
```

**AI Response Summary:**
Suggested approach:
1. Generate random operations (book with random times, cancel random existing)
2. After each operation, verify invariant holds
3. Run many iterations with different seeds
4. On failure, print the seed for reproducibility

```java
// Pseudocode suggestion
for (int i = 0; i < 1000; i++) {
    if (random.nextBoolean()) {
        // Random book
        service.book(randomProvider(), randomStart(), randomEnd());
    } else {
        // Random cancel
        service.cancel(randomExistingId());
    }
    assertNoOverlapsExist();
}
```

**Decision:** **Accepted and implemented**

```java
@RepeatedTest(100)
void invariant_noOverlappingActiveAppointments() {
    Random random = new Random();
    String[] providers = {"dr-a", "dr-b", "dr-c"};
    List<String> createdIds = new ArrayList<>();
    
    // Random operations
    for (int i = 0; i < 50; i++) {
        if (random.nextBoolean() || createdIds.isEmpty()) {
            // Book random appointment
            Instant start = Instant.now().plus(random.nextInt(100), ChronoUnit.HOURS);
            Instant end = start.plus(1 + random.nextInt(3), ChronoUnit.HOURS);
            String provider = providers[random.nextInt(providers.length)];
            
            BookingResult result = service.book(provider, start, end);
            if (result.getStatus() == BookingResult.Status.SUCCESS) {
                createdIds.add(result.getAppointment().getId());
            }
        } else {
            // Cancel random existing
            String id = createdIds.get(random.nextInt(createdIds.size()));
            service.cancel(id);
        }
    }
    
    // Verify invariant
    for (String provider : providers) {
        List<Appointment> active = store.findActiveByProvider(provider);
        for (int i = 0; i < active.size(); i++) {
            for (int j = i + 1; j < active.size(); j++) {
                assertFalse(
                    overlaps(active.get(i), active.get(j)),
                    "Found overlapping appointments for " + provider
                );
            }
        }
    }
}
```

**Affected Files:** `BookingServiceInvariantTest.java`

**Verification:** 100 iterations × 50 operations each = 5,000 random operations tested, all passed

---

### 10. Documentation Review

**AI Tool:** Claude (Anthropic)

**Prompt:**
```
Context: Finalizing documentation before submission.

Task: Review my assumptions document against the assessment requirements. 
Am I missing anything critical?

Current assumptions:
- UTC timestamps only
- In-memory storage (no persistence)
- Single JVM (no distributed locking)
- Half-open intervals [start, end)

Assessment requires:
- "A short note describing assumptions and the booking invariant"
- "A concise summary of limitations or follow-up risks"
```

**AI Response Summary:**
Suggested additions:
1. ✗ Missing: Explicit statement of the booking invariant
2. ✗ Missing: Limitations section (what this prototype doesn't handle)
3. ✓ Present: Core assumptions

**Decision:** **Accepted and added**

Added to `assumptions-and-limitations.md`:

```markdown
## Booking Invariant

At any point in time, for any given provider, no two appointments with 
status=ACTIVE may have overlapping time intervals.

Formally: For all appointments A, B where A.providerId = B.providerId 
and A.status = ACTIVE and B.status = ACTIVE and A ≠ B:
  NOT (A.start < B.end AND B.start < A.end)

## Limitations and Follow-up Risks

| Limitation | Risk | Mitigation for Production |
|------------|------|---------------------------|
| In-memory storage | Data lost on restart | Add database persistence |
| Single JVM | Can't scale horizontally | Distributed locking (Redis/DB) |
| No authentication | Anyone can book/cancel | Add auth layer |
| No idempotency keys | Duplicate requests may create duplicates | Add idempotency handling |
| No audit trail | Can't track who changed what | Add event sourcing or audit log |
```

**Affected Files:** `docs/assumptions-and-limitations.md`

**Verification:** Documentation reviewed against assessment checklist

---

## Verification Evidence Summary

| Test Category | Count | Status |
|---------------|-------|--------|
| Service unit tests | 45 | ✓ Passed |
| Concurrency tests | 12 | ✓ Passed |
| Invariant/randomized tests | 100 | ✓ Passed |
| API integration tests (MockMvc) | 18 | ✓ Passed |
| Application context test | 1 | ✓ Passed |
| **Total** | **160** | **All Passed** |

```
$ mvn clean test
[INFO] -------------------------------------------------------
[INFO]  T E S T S
[INFO] -------------------------------------------------------
[INFO] Running com.scheduler.service.BookingServiceTest
[INFO] Running com.scheduler.service.BookingServiceConcurrencyTest
[INFO] Running com.scheduler.service.BookingServiceInvariantTest
[INFO] Running com.scheduler.api.AppointmentControllerTest
[INFO] Running com.scheduler.ApplicationContextTest
[INFO] 
[INFO] Results:
[INFO] 
[INFO] Tests run: 160, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] BUILD SUCCESS
```

**Runtime Verification:**
- Application starts successfully on port 8080
- Swagger UI accessible at `http://localhost:8080/swagger-ui.html`
- Manual API test via curl confirmed booking, conflict, and cancellation flows

---

## Retrospective: What I Changed or Rejected

| AI Suggestion | Outcome | Reasoning |
|---------------|---------|-----------|
| CLI interface | Rejected | REST API more testable and appropriate for assessment |
| HATEOAS links | Rejected | Over-engineering for bounded prototype |
| Pagination | Rejected | Unnecessary for small dataset |
| `CancelResult.invalid(id, reason)` | Fixed | AI generated inconsistent method signature |
| Global synchronization | Rejected | Per-provider locking allows better concurrency |
| JUnit 4 syntax | Converted | JUnit 5 matches Spring Boot 3.x conventions |

## What I Would Do Differently

1. **Start with the invariant test first** — Writing the randomized invariant test earlier would have caught edge cases sooner

2. **Sketch API contract before implementation** — I added REST late; designing it upfront would have been cleaner

3. **Ask AI for edge cases explicitly** — The test coverage review prompt was valuable; I should have done this earlier in the process

---

## AI Tools Used

| Tool | Usage |
|------|-------|
| Claude (Anthropic) | Architecture decisions, code review, debugging, documentation |
| GitHub Copilot | Code completion, quick fixes, boilerplate generation |
