package dev.chronovault.sdk;

import java.util.List;

public record HealthCheckSpec(
    String id,
    String name,
    HealthCheckKind kind,
    List<String> command,
    boolean required,
    int timeoutSeconds,
    String workingSubdir,
    boolean shell
) {
    public enum HealthCheckKind {
        BUILD, TEST, TYPECHECK, RUNTIME, LINT, CUSTOM
    }

    public static HealthCheckSpec build(String name, List<String> command) {
        return new HealthCheckSpec(
            name.toLowerCase(), name, HealthCheckKind.BUILD, command,
            true, 600, ".", false
        );
    }

    public static HealthCheckSpec test(String name, List<String> command) {
        return new HealthCheckSpec(
            name.toLowerCase(), name, HealthCheckKind.TEST, command,
            true, 300, ".", false
        );
    }

    public static HealthCheckSpec custom(String id, String name, List<String> command) {
        return new HealthCheckSpec(
            id, name, HealthCheckKind.CUSTOM, command,
            false, 300, ".", false
        );
    }
}
