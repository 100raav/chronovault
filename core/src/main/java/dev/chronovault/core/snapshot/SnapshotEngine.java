package dev.chronovault.core.snapshot;

import dev.chronovault.core.domain.SnapshotManifest;
import dev.chronovault.core.project.ProjectContext;

import java.io.IOException;
import java.util.function.Consumer;

public interface SnapshotEngine {
    SnapshotManifest createSnapshot(ProjectContext ctx, Consumer<String> progressCallback) throws IOException;
    SnapshotManifest createIncremental(ProjectContext ctx, SnapshotManifest parent,
                                       Consumer<String> progressCallback) throws IOException;
    long restoreSnapshot(ProjectContext ctx, SnapshotManifest manifest, boolean strict) throws IOException;
}