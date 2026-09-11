package dev.chronovault.core.domain;

import java.util.UUID;

public record CheckpointId(String value) {
    public CheckpointId {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("CheckpointId cannot be null/blank");
    }

    public static CheckpointId generate() {
        return new CheckpointId("cp-" + UUID.randomUUID().toString().substring(0, 8));
    }

    public static CheckpointId of(String value) {
        return new CheckpointId(value);
    }

    @Override
    public String toString() { return value; }
}
