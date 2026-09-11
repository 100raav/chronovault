package dev.chronovault.core.domain;

import java.util.Objects;
import java.util.UUID;

public record ProjectId(String value) {
    public ProjectId {
        Objects.requireNonNull(value, "ProjectId cannot be null");
        if (value.isBlank()) throw new IllegalArgumentException("ProjectId cannot be blank");
    }

    public static ProjectId generate() {
        return new ProjectId(UUID.randomUUID().toString().substring(0, 8));
    }

    public static ProjectId of(String value) {
        return new ProjectId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
