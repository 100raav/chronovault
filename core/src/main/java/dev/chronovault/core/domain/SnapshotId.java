package dev.chronovault.core.domain;

import java.util.UUID;

public record SnapshotId(String value) {
    public SnapshotId {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("SnapshotId cannot be null/blank");
    }

    public static SnapshotId generate() {
        return new SnapshotId("sn-" + UUID.randomUUID().toString().substring(0, 8));
    }

    public static SnapshotId of(String value) {
        return new SnapshotId(value);
    }

    @Override
    public String toString() { return value; }
}