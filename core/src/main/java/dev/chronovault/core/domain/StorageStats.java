package dev.chronovault.core.domain;

public record StorageStats(
    long checkpoints,
    long snapshots,
    long objects,
    long physicalBytes,
    long logicalBytes,
    long protectedStates,
    long recoveryHistory
) {
    public double deduplicationRatio() {
        if (logicalBytes <= 0) return 0;
        return 1.0 - ((double) physicalBytes / logicalBytes);
    }
}