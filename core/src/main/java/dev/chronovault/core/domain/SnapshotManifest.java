package dev.chronovault.core.domain;

import java.time.Instant;
import java.util.List;

public record SnapshotManifest(
    SnapshotId snapshotId,
    ProjectId projectId,
    SnapshotType type,
    SnapshotId parentSnapshotId,
    Instant createdAt,
    List<ManifestEntry> entries,
    long totalLogicalBytes,
    long totalPhysicalBytes,
    long fileCount
) {
    public enum SnapshotType {
        FULL, INCREMENTAL
    }
}