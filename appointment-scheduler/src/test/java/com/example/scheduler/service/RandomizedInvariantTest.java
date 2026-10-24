package com.example.scheduler.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.scheduler.model.Appointment;
import com.example.scheduler.store.InMemoryAppointmentStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Differential test: random book/cancel sequences are replayed against a reference model
 * that decides conflicts in a *different way* from production code. Production compares
 * interval endpoints; the model marks 15-minute grid slots in a BitSet and checks whether
 * any slot is already taken. Seeded, so failures are reproducible.
 */
class RandomizedInvariantTest {

    private static final Instant DAY = Instant.parse("2026-03-02T00:00:00Z");
    private static final int SLOT_MINUTES = 15;
    private static final int SLOTS_PER_DAY = 24 * 60 / SLOT_MINUTES;

    @Test
    void serviceAgreesWithSlotBitmapModelOverManyRandomOperations() {
        for (long seed = 1; seed <= 20; seed++) {
            runScenario(seed, 1_000);
        }
    }

    private void runScenario(long seed, int operations) {
        Random rnd = new Random(seed);
        InMemoryAppointmentStore store = new InMemoryAppointmentStore();
        BookingService service = new BookingService(store);

        String[] providers = {"p1", "p2", "p3"};
        Map<String, BitSet> occupied = new HashMap<>();              // provider -> taken slots
        Map<String, int[]> activeRange = new HashMap<>();            // appointmentId -> {fromSlot,toSlot}
        Map<String, String> activeProvider = new HashMap<>();        // appointmentId -> provider
        List<String> allIds = new ArrayList<>();
        for (String p : providers) {
            occupied.put(p, new BitSet(SLOTS_PER_DAY));
        }

        for (int i = 0; i < operations; i++) {
            if (rnd.nextInt(100) < 70 || allIds.isEmpty()) {
                String provider = providers[rnd.nextInt(providers.length)];
                int from = rnd.nextInt(SLOTS_PER_DAY - 8);              // leave room for 8 slots
                int len = 1 + rnd.nextInt(8);                           // 15 min .. 2 h
                int to = from + len;

                boolean modelSaysFree = occupied.get(provider).get(from, to).isEmpty();
                BookingResult r = service.book(provider, "c", slot(from).toString(), slot(to).toString());

                if (modelSaysFree) {
                    assertEquals(BookingResult.Status.BOOKED, r.status(), ctx(seed, i, provider, from, to));
                    occupied.get(provider).set(from, to);
                    activeRange.put(r.appointment().id(), new int[] {from, to});
                    activeProvider.put(r.appointment().id(), provider);
                    allIds.add(r.appointment().id());
                } else {
                    assertEquals(BookingResult.Status.CONFLICT, r.status(), ctx(seed, i, provider, from, to));
                }
            } else {
                String id = allIds.get(rnd.nextInt(allIds.size()));
                CancelResult c = service.cancel(id);
                if (activeRange.containsKey(id)) {
                    assertEquals(CancelResult.Status.CANCELLED, c.status(), "seed " + seed + " op " + i);
                    int[] range = activeRange.remove(id);
                    occupied.get(activeProvider.remove(id)).clear(range[0], range[1]);
                } else {
                    assertEquals(CancelResult.Status.ALREADY_CANCELLED, c.status(), "seed " + seed + " op " + i);
                }
            }
        }

        assertNoActiveOverlaps(store.findAll());
        assertEquals(activeRange.size(), store.findAll().stream().filter(Appointment::isActive).count());
    }

    static void assertNoActiveOverlaps(List<Appointment> all) {
        List<Appointment> active = all.stream().filter(Appointment::isActive).toList();
        for (int i = 0; i < active.size(); i++) {
            for (int j = i + 1; j < active.size(); j++) {
                Appointment a = active.get(i);
                Appointment b = active.get(j);
                if (a.providerId().equals(b.providerId())) {
                    assertTrue(!a.overlaps(b.start(), b.end()),
                            "overlapping active appointments: " + a + " and " + b);
                }
            }
        }
    }

    private static Instant slot(int index) {
        return DAY.plusSeconds(index * SLOT_MINUTES * 60L);
    }

    private static String ctx(long seed, int op, String provider, int from, int to) {
        return "seed=" + seed + " op=" + op + " provider=" + provider + " slots=[" + from + "," + to + ")";
    }
}
