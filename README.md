# Appointment Scheduler

A Spring Boot REST API for booking, cancelling, and listing provider appointments. It prevents overlapping active appointments for the same provider, while allowing back-to-back bookings and appointments with different providers. Timestamps use UTC.

## Technology

- Java 17
- Spring Boot
- Maven
- In-memory storage


The API starts at `http://localhost:8080`. Run the tests with `mvn test`.

## Main endpoints

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/appointments` | Book an appointment |
| `POST` | `/api/appointments/{id}/cancel` | Cancel an appointment |
| `GET` | `/api/appointments` | List appointments; optionally filter with `?providerId=...` |

Appointments are stored in memory, so data is cleared when the application restarts. This project is a prototype and is not intended for production use.
