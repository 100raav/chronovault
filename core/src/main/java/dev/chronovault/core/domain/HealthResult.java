package dev.chronovault.core.domain;

import java.time.Instant;
import java.util.List;

public record HealthResult(
    boolean overallPass,
    HealthStatus overallStatus,
    List<HealthCheckResult> checks,
    Instant startedAt,
    Instant finishedAt,
    long totalDurationMs
) {
    public long passedCount() {
        return checks.stream().filter(HealthCheckResult::passed).count();
    }

    public long totalCount() {
        return checks.size();
    }

    public long failedCount() {
        return checks.stream().filter(c -> c.status() == HealthStatus.FAIL).count();
    }
}