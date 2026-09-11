package com.example.clock;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ClockTest {
    @Test
    void returnsValidTime() {
        assertTrue(Clock.isValid(Clock.now()));
    }
}