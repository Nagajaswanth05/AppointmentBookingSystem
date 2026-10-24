# REST API: How to Run and Test It

## 1. Clone, Build, and Start the API

1. **Clone the repository** from Git:

   ```bash
   git clone <url>
   ```

2. Open the project in your preferred IDE (e.g., **IntelliJ IDEA**).

3. **Configure and run the Spring Boot application:**
    - Create or open a **Spring Boot Run/Debug Configuration** for `com.example.scheduler.AppointmentSchedulerApplication` as shown below:  
      [IntelliJ IDEA Spring Boot Run Configuration](./images/Configuration.png)

4. Once started, you will see the Spring Boot banner and startup logs in your terminal (similar to the screenshot below):

   [Screenshot of terminal startup logs](./images/ApplicationLogs.png)

- **Server URL:** `http://localhost:8080` (you can configure or change the port using `server.port` in `src/main/resources/application.properties`).
- **Data persistence:** Data is stored in memory; restarting the application clears all existing data.

---

## 2. Swagger UI & Interactive Documentation

Open your browser and navigate to the Swagger UI: **[http://localhost:8080/swagger-ui/index.html](http://localhost:8080/swagger-ui/index.html)**

---

## 3. Endpoints & Request Examples

| **Method & Path** | **Purpose** | **Success Response** | **Error Responses** |
| --- | --- | --- | --- |
| `POST /api/appointments` | Book an appointment | `201 Created` + Appointment JSON | `400` (Invalid input), `409` (Conflict / Overlap) |
| `POST /api/appointments/{id}/cancel` | Cancel an appointment | `200 OK` + Appointment JSON (`status: CANCELLED`) | `404` (Unknown ID), `409` (Already cancelled) |
| `GET /api/appointments[?providerId=dr-a]` | List all active and cancelled appointments (sorted by provider, then start time) | `200 OK` + JSON array | None expected |

### Request Body Format for Booking

- **Content-Type requirement:** `application/json` (omitting this will result in a `415 Unsupported Media Type` response).
- **Fields:** All four fields are strings. Timestamps must be in **UTC** and **must end with `Z`**.

```json
{
  "providerId": "dr-a",
  "clientId": "pat-1",
  "start": "2026-03-02T09:00:00Z",
  "end": "2026-03-02T09:30:00Z"
}
```

### Error Response Shape

Every error follows a standard JSON format:

```json
{
  "error": "CONFLICT",
  "message": "...",
  "conflictingAppointmentId": "..."
}
```

- The `error` field will be one of: `INVALID_INPUT`, `CONFLICT`, `NOT_FOUND`, or `ALREADY_CANCELLED`.
- `conflictingAppointmentId` is included only when `error` is `CONFLICT`.

---

## 4. Testing via Swagger UI

1. Go to **[http://localhost:8080/swagger-ui/index.html](http://localhost:8080/swagger-ui/index.html)** in your browser.
2. Navigate to **`POST /api/appointments`** → click **Try it out**.
3. The example request body will be pre-filled. Click **Execute**. Expect a **`201 Created`** status and **copy the returned `id`**.
4. **Test Overlap:** Execute the exact same request body again. Expect a **`409 Conflict`** error with `error: CONFLICT`.
5. **Test Adjacent Booking:** Change the `start` and `end` times to `10:00:00Z` – `11:00:00Z` (adjacent to the first booking) and execute. Expect **`201 Created`**.
6. **Test Cancellation:**
    - Go to **`POST /api/appointments/{id}/cancel`**, paste the copied `id`, and execute. Expect **`200 OK`** with `status: CANCELLED`.
    - Run the cancellation again with the same `id` → expect **`409 ALREADY_CANCELLED`**.
    - Use an invalid ID like `nope` → expect **`404 Not Found`**.
7. **Re-booking:** Re-book the original `09:00 - 10:00` slot → expect **`201 Created`** (cancelling the previous appointment successfully freed up the slot).
8. **List Appointments:** Go to **`GET /api/appointments`** and execute to view both active and cancelled records. You can also filter by provider using the `providerId` parameter (e.g., `dr-a`).

---

## 5. Automated Tests

To run the automated test suite via Maven, use:

```bash
mvn clean test
```

- `AppointmentControllerTest` checks endpoint status codes and JSON responses using standalone MockMvc.
- `ApplicationContextTest` starts the real Spring application context and exercises the endpoint with MockMvc.
- Service, concurrency, and randomized invariant tests are also included and run automatically.
