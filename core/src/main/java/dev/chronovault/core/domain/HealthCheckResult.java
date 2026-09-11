package dev.chronovault.core.domain;

public record HealthCheckResult(
    String checkId,
    String name,
    String kind,
    HealthStatus status,
    boolean required,
    int exitCode,
    long durationMs,
    String outputTail,
    String errorTail
) {
    public boolean passed() {
        return status == HealthStatus.PASS;
    }
}