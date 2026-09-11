package dev.chronovault.core.health;

import dev.chronovault.core.checkpoint.CheckpointService;
import dev.chronovault.core.diff.DiffEngine;
import dev.chronovault.core.domain.*;
import dev.chronovault.core.project.ProjectContext;
import dev.chronovault.core.snapshot.SnapshotEngine;
import dev.chronovault.core.storage.MetadataStore;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Deterministic "What Broke It?" analysis.
 * Compares the last verified checkpoint against the current broken state and
 * produces classified evidence cards (FACT / OBSERVATION / HYPOTHESIS).
 */
public final class DiagnosticsService {
    private final CheckpointService checkpointService;
    private final SnapshotEngine snapshotEngine;
    private final MetadataStore metadataStore;
    private final DiffEngine diffEngine;

    public DiagnosticsService(CheckpointService checkpointService, SnapshotEngine snapshotEngine,
                              MetadataStore metadataStore, DiffEngine diffEngine) {
        this.checkpointService = checkpointService;
        this.snapshotEngine = snapshotEngine;
        this.metadataStore = metadataStore;
        this.diffEngine = diffEngine;
    }

    public record EvidenceCard(String kind, String severity, String title, String detail) {}

    public record Diagnosis(
        String lastHealthyCheckpoint,
        Instant lastHealthyAt,
        int changesSinceHealthy,
        List<DiffEngine.Change> changes,
        List<EvidenceCard> cards,
        boolean healthy
    ) {}

    public Diagnosis diagnose(ProjectContext ctx, HealthResult currentHealth) throws IOException {
        Optional<Checkpoint> lastVerified = checkpointService.findLastVerified(ctx);
        if (lastVerified.isEmpty()) {
            return new Diagnosis(null, null, 0, List.of(),
                List.of(new EvidenceCard("FACT", "INFO",
                    "No verified checkpoint exists",
                    "Create a checkpoint after a successful verification to enable diagnosis.")),
                true);
        }

        Checkpoint healthy = lastVerified.get();
        SnapshotManifest current = snapshotEngine.createSnapshot(ctx, null);
        SnapshotManifest healthySnapshot = metadataStore.getSnapshotManifest(ctx.projectId(), healthy.snapshotId())
            .orElse(null);

        boolean isHealthy = currentHealth != null && currentHealth.overallPass();

        List<DiffEngine.Change> changes = healthySnapshot == null
            ? List.of()
            : diffEngine.diff(healthySnapshot.entries(), current.entries()).changes();

        List<EvidenceCard> cards = new ArrayList<>();

        if (!isHealthy && currentHealth != null) {
            for (HealthCheckResult check : currentHealth.checks()) {
                if (!check.passed()) {
                    cards.add(new EvidenceCard("FACT", "ERROR",
                        check.name() + " failed (exit " + check.exitCode() + ")",
                        tail(check.errorTail()) + tail(check.outputTail())));
                }
            }
        }

        List<DiffEngine.Change> codeChanges = changes.stream()
            .filter(c -> looksLikeSource(c.path()))
            .sorted(Comparator.comparing(DiffEngine.Change::path))
            .toList();

        if (!isHealthy) {
            for (DiffEngine.Change c : codeChanges.stream().limit(8).toList()) {
                cards.add(new EvidenceCard(
                    "OBSERVATION", "WARN",
                    (c.kind() == DiffEngine.Change.ChangeKind.ADDED ? "Added " :
                     c.kind() == DiffEngine.Change.ChangeKind.DELETED ? "Deleted " : "Modified ") + c.path(),
                    "Changed since last verified checkpoint " + healthy.id()
                ));
            }
            if (codeChanges.isEmpty()) {
                cards.add(new EvidenceCard("HYPOTHESIS", "MED",
                    "No source changes since last verified state",
                    "The breakage may come from dependencies, generated files, environment, or configuration."));
            } else {
                cards.add(new EvidenceCard("HYPOTHESIS", "MED",
                    "Likely culprit — latest changed files",
                    "Review the " + codeChanges.size() + " changed file(s). The last modified files are the most likely cause."));
            }
        } else {
            cards.add(new EvidenceCard("FACT", "PASS",
                "Project is currently healthy",
                "No breakage detected at this moment."));
        }

        return new Diagnosis(
            healthy.id().value(),
            healthy.createdAt(),
            changes.size(),
            changes,
            cards,
            isHealthy
        );
    }

    private boolean looksLikeSource(String path) {
        return path.matches(".*\\.(java|kt|kts|py|js|ts|tsx|jsx|go|rs|c|cpp|h|hpp|cs|rb|php|sql|yaml|yml|json|xml|properties|toml)$");
    }

    private String tail(String s) {
        if (s == null || s.isBlank()) return "";
        String clean = s.lines().filter(l -> !l.isBlank()).reduce((a, b) -> a + "\\n" + b).orElse("");
        if (clean.length() > 400) clean = "…" + clean.substring(clean.length() - 400);
        return clean;
    }
}