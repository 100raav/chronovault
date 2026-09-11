package dev.chronovault.core.retention;

import dev.chronovault.core.domain.*;
import dev.chronovault.core.project.ProjectContext;
import dev.chronovault.core.storage.ContentStore;
import dev.chronovault.core.storage.DiskContentStore;
import dev.chronovault.core.storage.MetadataStore;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

public final class RetentionService {
    private final MetadataStore metadataStore;
    private final ContentStore contentStore;

    public RetentionService(MetadataStore metadataStore, ContentStore contentStore) {
        this.metadataStore = metadataStore;
        this.contentStore = contentStore;
    }

    public record RetentionReport(
        int checkpointsRemoved,
        int snapshotsRemoved,
        int objectsRemoved,
        long bytesFreed,
        List<String> details
    ) {}

    public RetentionReport applyPolicy(ProjectContext ctx) throws IOException {
        return applyPolicy(ctx, ctx.config().retention(), false);
    }

    public RetentionReport applyPolicy(ProjectContext ctx, VaultConfig.RetentionPolicy policy,
                                       boolean dryRun) throws IOException {
        List<String> details = new ArrayList<>();
        List<Checkpoint> checkpoints = new ArrayList<>(metadataStore.listCheckpoints(ctx.projectId()));
        checkpoints.sort(Comparator.comparing(Checkpoint::createdAt));

        Set<CheckpointId> keep = new LinkedHashSet<>();

        if (policy.keepPinned()) {
            checkpoints.stream().filter(Checkpoint::pinned)
                .sorted(Comparator.comparing(Checkpoint::createdAt))
                .forEach(c -> keep.add(c.id()));
        }
        if (policy.keepLastHealthy()) {
            checkpoints.stream()
                .filter(c -> c.status() == CheckpointStatus.VERIFIED)
                .max(Comparator.comparing(Checkpoint::createdAt))
                .ifPresent(c -> keep.add(c.id()));
        }
        // keep latest N
        if (policy.keepLatest() > 0) {
            checkpoints.stream()
                .sorted(Comparator.comparing(Checkpoint::createdAt).reversed())
                .limit(policy.keepLatest())
                .forEach(c -> keep.add(c.id()));
        }
        // keep last N days
        if (policy.keepDays() > 0) {
            Instant cutoff = Instant.now().minus(Duration.ofDays(policy.keepDays()));
            checkpoints.stream()
                .filter(c -> c.createdAt().isAfter(cutoff))
                .forEach(c -> keep.add(c.id()));
        }

        List<Checkpoint> removeTargets = checkpoints.stream()
            .filter(c -> !keep.contains(c.id()))
            .toList();

        Set<SnapshotId> keepSnapshots = new HashSet<>();
        metadataStore.listCheckpoints(ctx.projectId()).forEach(c -> keepSnapshots.add(c.snapshotId()));
        for (Checkpoint c : removeTargets) keepSnapshots.remove(c.snapshotId());

        if (dryRun) {
            details.add("Dry run: would remove " + removeTargets.size() + " checkpoints");
            return new RetentionReport(removeTargets.size(), 0, 0, 0, details);
        }

        for (Checkpoint c : removeTargets) {
            metadataStore.deleteCheckpoint(ctx.projectId(), c.id());
            details.add("Removed checkpoint " + c.id());
        }

        int[] snapshotsRemoved = {0};
        for (SnapshotId sid : metadataStore.allSnapshotIds()) {
            if (keepSnapshots.contains(sid)) continue;
            metadataStore.deleteSnapshot(ctx.projectId(), sid);
            snapshotsRemoved[0]++;
            details.add("Removed snapshot " + sid);
        }

        RetentionReport gcReport = gc(ctx, dryRun);
        return new RetentionReport(
            removeTargets.size(), snapshotsRemoved[0],
            gcReport.objectsRemoved(), gcReport.bytesFreed(), details);
    }

    public RetentionReport gc(ProjectContext ctx, boolean dryRun) throws IOException {
        Set<String> referenced = new HashSet<>(metadataStore.allContentHashes());

        int removed = 0;
        long freed = 0;
        Path objectsRoot = ((DiskContentStore) contentStore).getObjectsRoot();

        List<Path> candidates = new ArrayList<>();
        if (Files.exists(objectsRoot)) {
            try (DirectoryStream<Path> s1 = Files.newDirectoryStream(objectsRoot)) {
                for (Path a : s1) {
                    if (!Files.isDirectory(a)) continue;
                    try (DirectoryStream<Path> s2 = Files.newDirectoryStream(a)) {
                        for (Path b : s2) {
                            if (!Files.isDirectory(b)) continue;
                            try (DirectoryStream<Path> s3 = Files.newDirectoryStream(b)) {
                                for (Path obj : s3) {
                                    candidates.add(obj);
                                }
                            }
                        }
                    }
                }
            }
        }

        for (Path obj : candidates) {
            String hash = obj.getFileName().toString();
            if (!referenced.contains(hash)) {
                if (!dryRun) {
                    freed += Files.size(obj);
                    Files.deleteIfExists(obj);
                }
                removed++;
            }
        }
        if (!dryRun) {
            pruneEmptyDirectories(objectsRoot);
        }
        return new RetentionReport(0, 0, removed, freed, List.of());
    }

    /** Delete now-empty shard directories bottom-up; non-empty dirs are left untouched. */
    private void pruneEmptyDirectories(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            walk
                .sorted(Comparator.reverseOrder())
                .filter(p -> !p.equals(root))
                .filter(Files::isDirectory)
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {}
                });
        }
    }
}