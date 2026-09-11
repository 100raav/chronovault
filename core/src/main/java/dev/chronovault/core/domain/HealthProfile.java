package dev.chronovault.core.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public record HealthProfile(
    String name,
    int version,
    boolean failFast,
    List<HealthCheckDefinition> checks
) {
    public record HealthCheckDefinition(
        String id,
        String name,
        String kind,
        List<String> command,
        boolean required,
        int timeoutSeconds,
        String workingSubdir,
        boolean shell
    ) {
        public boolean isRequired() { return required; }
    }

    public HealthProfile {
        Objects.requireNonNull(name);
        checks = List.copyOf(checks);
    }

    public static HealthProfile of(String name, HealthCheckDefinition... checks) {
        return new HealthProfile(name, 1, true, Arrays.asList(checks));
    }
}