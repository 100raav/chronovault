package com.example.clock;

/**
 * A tiny, dependency-free clock that formats the current time.
 * Used as the CHRONOVAULT demonstration project.
 */
public final class Clock {
    private Clock() {}

    public static String now() {
        return java.time.LocalTime.now().withNano(0).toString();
    }

    public static boolean isValid(String time) {
        return time != null && time.matches("\\d{2}:\\d{2}:\\d{2}");
    }
}