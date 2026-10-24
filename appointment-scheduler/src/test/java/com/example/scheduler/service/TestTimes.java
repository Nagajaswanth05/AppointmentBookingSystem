package com.example.scheduler.service;

/** Test helper: builds UTC timestamps on a fixed day. */
final class TestTimes {
    private TestTimes() { }

    /** at("09:30") -> 2026-03-02T09:30:00Z */
    static String at(String hhmm) {
        return "2026-03-02T" + hhmm + ":00Z";
    }
}
