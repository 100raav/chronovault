package dev.chronovault.core.domain;

import java.util.UUID;

public record OperationId(String value) {
    public OperationId {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("OperationId cannot be null/blank");
    }

    public static OperationId generate() {
        return new OperationId("op-" + UUID.randomUUID().toString().substring(0, 8));
    }

    public static OperationId of(String value) { return new OperationId(value); }

    @Override
    public String toString() { return value; }
}