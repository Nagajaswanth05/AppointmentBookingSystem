package com.example.scheduler.service;

import static com.example.scheduler.service.TestTimes.at;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.scheduler.model.Appointment;
import com.example.scheduler.model.AppointmentStatus;
import com.example.scheduler.store.AppointmentStore;
import com.example.scheduler.store.InMemoryAppointmentStore;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class BookingServiceTest {

    private AppointmentStore store;
    private BookingService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryAppointmentStore();
        service = new BookingService(store);
    }

    private BookingResult book(String provider, String from, String to) {
        return service.book(provider, "client-1", at(from), at(to));
    }

    // ------------------------------------------------------------------ AC1
    @Nested
    class SuccessfulBooking {

        @Test
        void validBookingIsStoredAsActive() {
            BookingResult r = book("dr-a", "09:00", "09:30");

            assertEquals(BookingResult.Status.BOOKED, r.status());
            assertTrue(r.isBooked());
            Appointment a = r.appointment();
            assertNotNull(a.id());
            assertEquals("dr-a", a.providerId());
            assertEquals("client-1", a.clientId());
            assertEquals(Instant.parse("2026-03-02T09:00:00Z"), a.start());
            assertEquals(Instant.parse("2026-03-02T09:30:00Z"), a.end());
            assertEquals(AppointmentStatus.ACTIVE, a.status());
            assertEquals(a, store.findById(a.id()).orElseThrow());
        }

        @Test
        void eachBookingGetsAUniqueId() {
            String id1 = book("dr-a", "09:00", "09:30").appointment().id();
            String id2 = book("dr-a", "10:00", "10:30").appointment().id();
            assertNotEquals(id1, id2);
        }

        @Test
        void oneSecondAppointmentIsValid() {
            BookingResult r = service.book("dr-a", "c", "2026-03-02T09:00:00Z", "2026-03-02T09:00:01Z");
            assertTrue(r.isBooked());
        }
    }

    // ------------------------------------------------------------------ AC2
    @Nested
    class SameProviderOverlap {

        @BeforeEach
        void existingNineToTenThirty() {
            assertTrue(book("dr-a", "09:00", "10:30").isBooked());
        }

        @ParameterizedTest(name = "[{index}] {0}: {1}-{2} conflicts")
        @CsvSource({
                "identical,            09:00, 10:30",
                "partial at start,     08:00, 09:01",
                "partial at end,       10:29, 11:00",
                "contained inside,     09:30, 10:00",
                "containing existing,  08:00, 12:00",
                "same start longer,    09:00, 11:00",
                "same end earlier,     08:00, 10:30",
        })
        void overlappingBookingIsRejectedAndStoreUnchanged(String label, String from, String to) {
            List<Appointment> before = store.findAll();

            BookingResult r = book("dr-a", from.trim(), to.trim());

            assertEquals(BookingResult.Status.CONFLICT, r.status(), label);
            assertNull(r.appointment());
            assertNotNull(r.conflictingAppointmentId());
            assertEquals(before, store.findAll(), "a rejected booking must not change stored bookings");
        }
    }

    @Nested
    class DifferentProviders {

        @Test
        void sameSlotForDifferentProvidersDoesNotConflict() {
            assertTrue(book("dr-a", "09:00", "10:00").isBooked());
            assertTrue(book("dr-b", "09:00", "10:00").isBooked());
            assertEquals(1, store.findByProvider("dr-a").size());
            assertEquals(1, store.findByProvider("dr-b").size());
        }

        @Test
        void providerIdsAreCaseSensitiveExactMatches() {
            assertTrue(book("dr-a", "09:00", "10:00").isBooked());
            assertTrue(book("DR-A", "09:00", "10:00").isBooked());
        }
    }

    // ------------------------------------------------------------------ AC3
    @Nested
    class Adjacency {

        @Test
        void nextStartsExactlyWhenPreviousEnds() {
            assertTrue(book("dr-a", "09:00", "10:00").isBooked());
            assertTrue(book("dr-a", "10:00", "11:00").isBooked());
        }

        @Test
        void nextEndsExactlyWhenPreviousStarts() {
            assertTrue(book("dr-a", "10:00", "11:00").isBooked());
            assertTrue(book("dr-a", "09:00", "10:00").isBooked());
        }

        @Test
        void fillingTheGapBetweenTwoAppointmentsExactly() {
            assertTrue(book("dr-a", "09:00", "10:00").isBooked());
            assertTrue(book("dr-a", "11:00", "12:00").isBooked());
            assertTrue(book("dr-a", "10:00", "11:00").isBooked());
            // The calendar is now full 09:00-12:00. Anything even a second into it conflicts.
            assertEquals(BookingResult.Status.CONFLICT,
                    service.book("dr-a", "c", "2026-03-02T09:30:00Z", "2026-03-02T10:00:01Z").status());
            // ...while 12:00 onward is still free (adjacent to the 11:00-12:00 slot).
            assertTrue(book("dr-a", "12:00", "12:30").isBooked());
        }
    }

    // ------------------------------------------------------------------ AC4
    @Nested
    class Cancellation {

        @Test
        void cancelledAppointmentNoLongerBlocksItsTime() {
            Appointment a = book("dr-a", "09:00", "10:00").appointment();

            CancelResult c = service.cancel(a.id());

            assertEquals(CancelResult.Status.CANCELLED, c.status());
            assertTrue(c.isCancelled());
            assertEquals(AppointmentStatus.CANCELLED, c.appointment().status());
            assertTrue(book("dr-a", "09:00", "10:00").isBooked(), "exact slot is free again");
            assertEquals(BookingResult.Status.CONFLICT, book("dr-a", "08:30", "09:30").status(),
                    "...and the new booking now blocks the slot");
        }

        @Test
        void cancelledAppointmentStaysStoredWithCancelledStatus() {
            Appointment a = book("dr-a", "09:00", "10:00").appointment();
            service.cancel(a.id());
            assertEquals(AppointmentStatus.CANCELLED, store.findById(a.id()).orElseThrow().status());
            assertEquals(1, store.findAll().size());
        }

        @Test
        void cancelledAppointmentDoesNotBlockPartialOverlap() {
            Appointment a = book("dr-a", "09:00", "10:00").appointment();
            service.cancel(a.id());
            assertTrue(book("dr-a", "09:30", "10:30").isBooked());
        }

        @Test
        void cancellingOneAppointmentDoesNotFreeAnother() {
            Appointment first = book("dr-a", "09:00", "10:00").appointment();
            book("dr-a", "10:00", "11:00");
            service.cancel(first.id());
            assertEquals(BookingResult.Status.CONFLICT, book("dr-a", "10:30", "11:30").status());
            assertTrue(book("dr-a", "09:00", "10:00").isBooked());
        }

        @Test
        void cancellingMissingAppointmentReturnsNotFound() {
            CancelResult c = service.cancel("does-not-exist");
            assertEquals(CancelResult.Status.NOT_FOUND, c.status());
            assertFalse(c.message().isBlank());
            assertEquals(0, store.findAll().size());
        }

        @Test
        void cancellingTwiceReturnsAlreadyCancelled() {
            Appointment a = book("dr-a", "09:00", "10:00").appointment();
            assertEquals(CancelResult.Status.CANCELLED, service.cancel(a.id()).status());
            CancelResult second = service.cancel(a.id());
            assertEquals(CancelResult.Status.ALREADY_CANCELLED, second.status());
            assertFalse(second.message().isBlank());
            assertEquals(AppointmentStatus.CANCELLED, store.findById(a.id()).orElseThrow().status());
        }

        @Test
        void rebookedSlotStaysProtectedAfterOldAppointmentIsCancelledAgain() {
            Appointment old = book("dr-a", "09:00", "10:00").appointment();
            service.cancel(old.id());
            Appointment replacement = book("dr-a", "09:00", "10:00").appointment();
            // A repeat cancel of the OLD id must not free the replacement's slot.
            assertEquals(CancelResult.Status.ALREADY_CANCELLED, service.cancel(old.id()).status());
            assertEquals(BookingResult.Status.CONFLICT, book("dr-a", "09:00", "10:00").status());
            assertTrue(store.findById(replacement.id()).orElseThrow().isActive());
        }
    }

    // ------------------------------------------------------------------ AC5
    @Nested
    class InvalidInput {

        private void assertRejectedWithoutChange(BookingResult r, List<Appointment> before) {
            assertEquals(BookingResult.Status.INVALID_INPUT, r.status());
            assertFalse(r.message().isBlank());
            assertNull(r.appointment());
            assertEquals(before, store.findAll(), "invalid input must not change stored bookings");
        }

        @BeforeEach
        void oneExistingBooking() {
            assertTrue(book("dr-a", "09:00", "10:00").isBooked());
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {" ", "\t"})
        void missingProviderId(String provider) {
            List<Appointment> before = store.findAll();
            assertRejectedWithoutChange(service.book(provider, "c", at("12:00"), at("13:00")), before);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {" ", "\t"})
        void missingClientId(String client) {
            List<Appointment> before = store.findAll();
            assertRejectedWithoutChange(service.book("dr-a", client, at("12:00"), at("13:00")), before);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {
                " ",
                "not-a-timestamp",
                "2026-03-02",                       // date only
                "2026-03-02T12:00:00",              // no zone
                "2026-03-02T12:00:00+00:00",        // offset instead of Z
                "2026-03-02T12:00:00+05:30",        // non-UTC offset
                "2026-03-02T12:00:00z",             // lower-case z
                "2026-13-02T12:00:00Z",             // month 13
                "2026-02-30T12:00:00Z",             // impossible date
                "2026-03-02T25:00:00Z",             // hour 25
                "2026-03-02 12:00:00Z",             // space instead of T
                "1772452800",                       // epoch seconds
        })
        void invalidStartTimestamp(String badStart) {
            List<Appointment> before = store.findAll();
            assertRejectedWithoutChange(service.book("dr-a", "c", badStart, at("13:00")), before);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {" ", "garbage", "2026-03-02T13:00:00", "2026-03-02T13:00:00+01:00", "2026-03-02T13:60:00Z"})
        void invalidEndTimestamp(String badEnd) {
            List<Appointment> before = store.findAll();
            assertRejectedWithoutChange(service.book("dr-a", "c", at("12:00"), badEnd), before);
        }

        @ParameterizedTest(name = "[{index}] {0} -> {1}")
        @CsvSource({
                "12:00, 12:00",   // zero duration
                "13:00, 12:00",   // negative duration
                "12:00, 11:59",
        })
        void nonPositiveDuration(String from, String to) {
            List<Appointment> before = store.findAll();
            assertRejectedWithoutChange(service.book("dr-a", "c", at(from), at(to)), before);
        }

        @Test
        void invalidInputThatWouldAlsoOverlapIsReportedAsInvalidNotConflict() {
            List<Appointment> before = store.findAll();
            assertRejectedWithoutChange(service.book("dr-a", "c", at("09:30"), at("09:30")), before);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {" "})
        void cancelWithMissingIdentifier(String id) {
            List<Appointment> before = store.findAll();
            CancelResult c = service.cancel(id);
            assertEquals(CancelResult.Status.INVALID_INPUT, c.status());
            assertEquals(before, store.findAll());
        }
    }

    // ------------------------------------------------------------------ misc
    @Test
    void listAppointmentsIsSortedAndFilterable() {
        book("dr-b", "10:00", "11:00");
        book("dr-a", "11:00", "12:00");
        book("dr-a", "09:00", "10:00");

        List<Appointment> all = service.listAppointments(null);
        assertEquals(List.of("dr-a", "dr-a", "dr-b"), all.stream().map(Appointment::providerId).toList());
        assertEquals(Instant.parse("2026-03-02T09:00:00Z"), all.get(0).start());
        assertEquals(2, service.listAppointments("dr-a").size());
        assertEquals(0, service.listAppointments("nobody").size());
    }
}
