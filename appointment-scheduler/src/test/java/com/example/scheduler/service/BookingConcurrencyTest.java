package com.example.scheduler.service;

import static com.example.scheduler.service.TestTimes.at;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.scheduler.model.Appointment;
import com.example.scheduler.store.InMemoryAppointmentStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Concurrency tests. All workers are released together through a start gate to maximise
 * contention on the check-then-insert window. They are probabilistic by nature: a pass is
 * strong evidence, not proof. (Mutation check: removing the synchronized block from
 * BookingService.book makes these fail; see docs/ai-usage-record.md.)
 */
@Timeout(60)
class BookingConcurrencyTest {

    private static final int THREADS = 32;

    /** Runs all tasks concurrently behind a start gate and returns their results. */
    private static <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "workers failed to start");
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @RepeatedTest(20)
    void exactlyOneOfManyIdenticalConcurrentBookingsSucceeds() throws Exception {
        InMemoryAppointmentStore store = new InMemoryAppointmentStore();
        BookingService service = new BookingService(store);

        List<Callable<BookingResult>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            String client = "client-" + i;
            tasks.add(() -> service.book("dr-a", client, at("09:00"), at("10:00")));
        }
        List<BookingResult> results = runConcurrently(tasks);

        long booked = results.stream().filter(BookingResult::isBooked).count();
        long conflicts = results.stream().filter(r -> r.status() == BookingResult.Status.CONFLICT).count();
        assertEquals(1, booked);
        assertEquals(THREADS - 1, conflicts);
        assertEquals(1, store.findAll().size(), "losers must not leave anything behind");
    }

    @RepeatedTest(10)
    void randomOverlappingBookingsNeverProduceOverlappingActiveAppointments() throws Exception {
        InMemoryAppointmentStore store = new InMemoryAppointmentStore();
        BookingService service = new BookingService(store);
        Random seeds = new Random();

        List<Callable<BookingResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            Random rnd = new Random(seeds.nextLong());
            int startMin = rnd.nextInt(8 * 60);             // within an 8 h window -> heavy overlap
            int lenMin = 10 + rnd.nextInt(80);
            Instant s = Instant.parse("2026-03-02T00:00:00Z").plusSeconds(startMin * 60L);
            Instant e = s.plusSeconds(lenMin * 60L);
            tasks.add(() -> service.book("dr-a", "c", s.toString(), e.toString()));
        }
        List<BookingResult> results = runConcurrently(tasks);

        long booked = results.stream().filter(BookingResult::isBooked).count();
        List<Appointment> stored = store.findAll();
        assertEquals(booked, stored.size(), "every success is stored, every failure leaves nothing");
        assertTrue(booked >= 1);
        RandomizedInvariantTest.assertNoActiveOverlaps(stored);
    }

    @Test
    void differentProvidersBookingTheSameSlotConcurrentlyAllSucceed() throws Exception {
        InMemoryAppointmentStore store = new InMemoryAppointmentStore();
        BookingService service = new BookingService(store);

        List<Callable<BookingResult>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            String provider = "dr-" + i;
            tasks.add(() -> service.book(provider, "c", at("09:00"), at("10:00")));
        }
        List<BookingResult> results = runConcurrently(tasks);

        assertEquals(THREADS, results.stream().filter(BookingResult::isBooked).count());
        assertEquals(THREADS, store.findAll().size());
    }

    @RepeatedTest(20)
    void racingCancelsOfOneAppointmentYieldExactlyOneCancelled() throws Exception {
        InMemoryAppointmentStore store = new InMemoryAppointmentStore();
        BookingService service = new BookingService(store);
        String id = service.book("dr-a", "c", at("09:00"), at("10:00")).appointment().id();

        List<Callable<CancelResult>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            tasks.add(() -> service.cancel(id));
        }
        List<CancelResult> results = runConcurrently(tasks);

        assertEquals(1, results.stream().filter(CancelResult::isCancelled).count());
        assertEquals(THREADS - 1, results.stream()
                .filter(r -> r.status() == CancelResult.Status.ALREADY_CANCELLED).count());
    }

    @RepeatedTest(20)
    void bookingsRacingWithACancelNeverDoubleBookTheFreedSlot() throws Exception {
        InMemoryAppointmentStore store = new InMemoryAppointmentStore();
        BookingService service = new BookingService(store);
        String id = service.book("dr-a", "c", at("09:00"), at("10:00")).appointment().id();

        List<Callable<Object>> tasks = new ArrayList<>();
        tasks.add(() -> service.cancel(id));
        for (int i = 0; i < THREADS; i++) {
            tasks.add(() -> service.book("dr-a", "other", at("09:00"), at("10:00")));
        }
        List<Object> results = runConcurrently(tasks);

        long newBookings = results.stream()
                .filter(r -> r instanceof BookingResult br && br.isBooked()).count();
        // The original is cancelled in every interleaving. At most one replacement can win,
        // and it can only win if it was serialised after the cancel.
        assertTrue(newBookings <= 1, "double booking of the freed slot: " + newBookings);
        long active = store.findAll().stream().filter(Appointment::isActive).count();
        assertEquals(newBookings, active);
        RandomizedInvariantTest.assertNoActiveOverlaps(store.findAll());
    }
}
