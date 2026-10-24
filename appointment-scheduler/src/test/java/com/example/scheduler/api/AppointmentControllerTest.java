package com.example.scheduler.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.example.scheduler.api.controller.AppointmentController;
import com.example.scheduler.api.exception.ApiExceptionHandler;
import com.example.scheduler.service.BookingService;
import com.example.scheduler.store.InMemoryAppointmentStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * HTTP-level tests of the REST adapter using standalone MockMvc (no Spring context), so they are
 * fast and exercise exactly the controller + JSON + status-code mapping. Business rules are
 * covered in depth by BookingServiceTest; here we check each rule is reachable and mapped correctly.
 */


class AppointmentControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        BookingService service = new BookingService(new InMemoryAppointmentStore());
        mvc = standaloneSetup(new AppointmentController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    // ------------------------------------------------------------------ helpers
    private static String body(String provider, String client, String start, String end) {
        ObjectNode n = JSON.createObjectNode();
        if (provider != null) n.put("providerId", provider);
        if (client != null) n.put("clientId", client);
        if (start != null) n.put("start", start);
        if (end != null) n.put("end", end);
        return n.toString();
    }

    private static String at(String hhmm) {
        return "2026-03-02T" + hhmm + ":00Z";
    }

    private record Reply(int status, String contentType, JsonNode json) { }

    private Reply send(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        MvcResult r = mvc.perform(request).andReturn();
        String text = r.getResponse().getContentAsString();
        return new Reply(r.getResponse().getStatus(), r.getResponse().getContentType(),
                text.isEmpty() ? null : JSON.readTree(text));
    }

    private Reply book(String provider, String client, String start, String end) throws Exception {
        return send(post("/api/appointments").contentType(MediaType.APPLICATION_JSON)
                .content(body(provider, client, start, end)));
    }

    private Reply book(String provider, String from, String to) throws Exception {
        return book(provider, "pat-1", at(from), at(to));
    }

    private Reply cancel(String id) throws Exception {
        return send(post("/api/appointments/" + id + "/cancel"));
    }

    private JsonNode list(String providerOrNull) throws Exception {
        var req = get("/api/appointments");
        if (providerOrNull != null) req = req.param("providerId", providerOrNull);
        Reply r = send(req);
        assertEquals(200, r.status());
        assertTrue(r.json().isArray());
        return r.json();
    }

    // ------------------------------------------------------------------ AC1 booking
    @Test
    void validBookingReturns201WithAppointmentJson() throws Exception {
        Reply r = book("dr-a", "pat-1", at("09:00"), at("09:30"));

        assertEquals(201, r.status());
        assertTrue(r.contentType().startsWith("application/json"), r.contentType());
        assertFalse(r.json().get("id").asText().isBlank());
        assertEquals("dr-a", r.json().get("providerId").asText());
        assertEquals("pat-1", r.json().get("clientId").asText());
        assertEquals("2026-03-02T09:00:00Z", r.json().get("start").asText());
        assertEquals("2026-03-02T09:30:00Z", r.json().get("end").asText());
        assertEquals("ACTIVE", r.json().get("status").asText());
    }

    // ------------------------------------------------------------------ AC2 / AC3
    @Test
    void overlapReturns409NamingTheBlockingAppointmentAndStoresNothing() throws Exception {
        String firstId = book("dr-a", "09:00", "10:00").json().get("id").asText();

        Reply r = book("dr-a", "09:30", "10:30");

        assertEquals(409, r.status());
        assertEquals("CONFLICT", r.json().get("error").asText());
        assertEquals(firstId, r.json().get("conflictingAppointmentId").asText());
        assertFalse(r.json().get("message").asText().isBlank());
        assertEquals(1, list(null).size());
    }

    @Test
    void differentProvidersDoNotConflict() throws Exception {
        assertEquals(201, book("dr-a", "09:00", "10:00").status());
        assertEquals(201, book("dr-b", "09:00", "10:00").status());
    }

    @Test
    void adjacentAppointmentsAreAllowedOnBothSides() throws Exception {
        assertEquals(201, book("dr-a", "09:00", "10:00").status());
        assertEquals(201, book("dr-a", "10:00", "11:00").status());
        assertEquals(201, book("dr-a", "08:00", "09:00").status());
        assertEquals(3, list("dr-a").size());
    }

    // ------------------------------------------------------------------ AC4 cancellation
    @Test
    void cancelReturns200WithCancelledStatusAndFreesTheSlot() throws Exception {
        String id = book("dr-a", "09:00", "10:00").json().get("id").asText();

        Reply c = cancel(id);

        assertEquals(200, c.status());
        assertEquals(id, c.json().get("id").asText());
        assertEquals("CANCELLED", c.json().get("status").asText());
        assertEquals(201, book("dr-a", "09:00", "10:00").status(), "slot is free again");
    }

    @Test
    void cancellingTwiceReturns409AlreadyCancelled() throws Exception {
        String id = book("dr-a", "09:00", "10:00").json().get("id").asText();
        assertEquals(200, cancel(id).status());

        Reply second = cancel(id);

        assertEquals(409, second.status());
        assertEquals("ALREADY_CANCELLED", second.json().get("error").asText());
    }

    @Test
    void cancellingUnknownIdReturns404() throws Exception {
        Reply r = cancel("does-not-exist");
        assertEquals(404, r.status());
        assertEquals("NOT_FOUND", r.json().get("error").asText());
        assertFalse(r.json().get("message").asText().isBlank());
    }

    // ------------------------------------------------------------------ AC5 invalid input
    @ParameterizedTest(name = "[{index}] start={0} end={1}")
    @CsvSource(nullValues = "NULL", value = {
            "2026-03-02T12:00:00+05:30, 2026-03-02T13:00:00Z",   // offset, not UTC
            "2026-03-02T12:00:00,       2026-03-02T13:00:00Z",   // no zone
            "garbage,                   2026-03-02T13:00:00Z",
            "2026-13-02T12:00:00Z,      2026-03-02T13:00:00Z",   // month 13
            "2026-03-02T12:00:00Z,      garbage",
            "2026-03-02T12:00:00Z,      2026-03-02T12:00:00Z",   // zero duration
            "2026-03-02T13:00:00Z,      2026-03-02T12:00:00Z",   // negative duration
            "NULL,                      2026-03-02T13:00:00Z",   // missing start
            "2026-03-02T12:00:00Z,      NULL",                   // missing end
    })
    void invalidTimesReturn400AndChangeNothing(String start, String end) throws Exception {
        assertEquals(201, book("dr-a", "09:00", "10:00").status());

        Reply r = book("dr-a", "pat-1", start, end);

        assertEquals(400, r.status());
        assertEquals("INVALID_INPUT", r.json().get("error").asText());
        assertFalse(r.json().get("message").asText().isBlank());
        assertEquals(1, list(null).size(), "invalid input must not change stored bookings");
    }

    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {"NULL, pat-1", "'', pat-1", "' ', pat-1", "dr-a, NULL", "dr-a, ''", "dr-a, ' '"})
    void missingIdentifiersReturn400AndChangeNothing(String provider, String client) throws Exception {
        Reply r = book(provider, client, at("12:00"), at("13:00"));

        assertEquals(400, r.status());
        assertEquals("INVALID_INPUT", r.json().get("error").asText());
        assertEquals(0, list(null).size());
    }

    @Test
    void malformedJsonReturns400InTheSameErrorShape() throws Exception {
        Reply r = send(post("/api/appointments").contentType(MediaType.APPLICATION_JSON).content("{not json"));
        assertEquals(400, r.status());
        assertEquals("INVALID_INPUT", r.json().get("error").asText());
    }

    @Test
    void emptyBodyReturns400() throws Exception {
        Reply r = send(post("/api/appointments").contentType(MediaType.APPLICATION_JSON));
        assertEquals(400, r.status());
        assertEquals("INVALID_INPUT", r.json().get("error").asText());
    }

    @Test
    void wrongContentTypeIsRejectedWith415() throws Exception {
        MvcResult r = mvc.perform(post("/api/appointments").contentType(MediaType.TEXT_PLAIN)
                .content(body("dr-a", "pat-1", at("09:00"), at("10:00")))).andReturn();
        assertEquals(415, r.getResponse().getStatus());
    }

    // ------------------------------------------------------------------ listing
    @Test
    void listIsEmptyArrayWhenNothingBooked() throws Exception {
        assertEquals(0, list(null).size());
    }

    @Test
    void listIsSortedByProviderThenStartAndIncludesCancelledWithStatus() throws Exception {
        book("dr-b", "10:00", "11:00");
        String cancelledId = book("dr-a", "11:00", "12:00").json().get("id").asText();
        book("dr-a", "09:00", "10:00");
        cancel(cancelledId);

        JsonNode all = list(null);

        assertEquals(3, all.size());
        assertEquals("dr-a", all.get(0).get("providerId").asText());
        assertEquals("2026-03-02T09:00:00Z", all.get(0).get("start").asText());
        assertEquals("ACTIVE", all.get(0).get("status").asText());
        assertEquals("dr-a", all.get(1).get("providerId").asText());
        assertEquals("CANCELLED", all.get(1).get("status").asText());
        assertEquals("dr-b", all.get(2).get("providerId").asText());
    }

    @Test
    void listCanBeFilteredByProvider() throws Exception {
        book("dr-a", "09:00", "10:00");
        book("dr-b", "09:00", "10:00");
        book("dr-b", "10:00", "11:00");

        assertEquals(1, list("dr-a").size());
        assertEquals(2, list("dr-b").size());
        assertEquals(0, list("nobody").size());
    }
}
