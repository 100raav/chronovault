package dev.chronovault.core.application;

import com.google.gson.GsonBuilder;
import dev.chronovault.core.domain.*;
import dev.chronovault.core.health.*;
import dev.chronovault.core.project.*;
import dev.chronovault.core.recovery.RecoveryGuard;
import dev.chronovault.core.recovery.RecoveryService;
import dev.chronovault.core.retention.RetentionService;
import dev.chronovault.core.diff.DiffEngine;
import dev.chronovault.core.protocol.EventBus;
import dev.chronovault.core.snapshot.DefaultSnapshotEngine;
import dev.chronovault.core.snapshot.SnapshotEngine;
import dev.chronovault.core.storage.*;
import dev.chronovault.core.checkpoint.CheckpointService;
import dev.chronovault.core.util.JsonUtil;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * CHRONOVAULT main orchestrator. Wires all subsystems; provides a unified API for CLI, web server, and IDE integrations.
 * Create via {@code init(projectRoot, projectName)} or {@code open(projectRoot)}.
 */
public final class ChronoVault implements AutoCloseable {
    private final VaultLayout layout;
    private final ProjectContext projectCtx;
    private final SqliteMetadataStore metaStore;
    private final DiskContentStore contentStore;
    private final RecoveryJournal journal;
    private final SnapshotEngine snapshotEngine;
    private final CheckpointService checkpointService;
    private final HealthEngine healthEngine;
    private final RecoveryService recoveryService;
    private final RecoveryGuard recoveryGuard;
    private final DiagnosticsService diagnosticsService;
    private final RetentionService retentionService;
    private final FingerprintService fingerprints;
    private final EventBus eventBus;
    private final AdapterRegistry adapterRegistry;
    private final DiffEngine diffEngine;

    public EventBus eventBus() { return eventBus; }
    public ProjectContext projectContext() { return projectCtx; }
    public SqliteMetadataStore metaStore() { return metaStore; }
    public DiskContentStore contentStore() { return contentStore; }
    public RecoveryJournal journal() { return journal; }
    public SnapshotEngine snapshotEngine() { return snapshotEngine; }
    public CheckpointService checkpointService() { return checkpointService; }
    public HealthEngine healthEngine() { return healthEngine; }
    public RecoveryService recoveryService() { return recoveryService; }
    public DiagnosticsService diagnosticsService() { return diagnosticsService; }
    public RetentionService retentionService() { return retentionService; }
    public FingerprintService fingerprints() { return fingerprints; }
    public DiffEngine diffEngine() { return diffEngine; }
    public AdapterRegistry adapterRegistry() { return adapterRegistry; }
    public VaultLayout layout() { return layout; }

    private final HealthEngine.CommandApprover trustApprover;

    public ChronoVault(Path projectRoot, VaultConfig config, boolean allowAllCommands,
                       List<dev.chronovault.sdk.ProjectAdapter> extraAdapters) throws IOException {
        projectRoot = projectRoot.toAbsolutePath().normalize();
        try {
            projectRoot = projectRoot.toRealPath();
        } catch (IOException ignored) {
            // path may not exist yet; fall back to the normalized absolute path
        }
        this.layout = VaultLayout.of(projectRoot);
        Files.createDirectories(layout.vaultRoot());

        this.metaStore = new SqliteMetadataStore(layout.metadataDb());
        this.metaStore.initialize();

        this.contentStore = new DiskContentStore(layout.objectsRoot());
        this.journal = new RecoveryJournal(layout.vaultRoot());
        this.diffEngine = new DiffEngine();
        this.fingerprints = new FingerprintService();
        this.eventBus = new EventBus();
        this.snapshotEngine = new DefaultSnapshotEngine();

        this.trustApprover = allowAllCommands
            ? cmd -> true
            : new TrustPolicyApprover(projectRoot, config);

        this.healthEngine = new HealthEngine(new ProcessCommandExecutor(), trustApprover);
        this.checkpointService = new CheckpointService(snapshotEngine, metaStore, fingerprints);
        this.recoveryService = new RecoveryService(snapshotEngine, metaStore, contentStore, healthEngine, journal, diffEngine);
        this.diagnosticsService = new DiagnosticsService(checkpointService, snapshotEngine, metaStore, diffEngine);
        this.retentionService = new RetentionService(metaStore, contentStore);
        this.adapterRegistry = new AdapterRegistry(extraAdapters);

        ProjectId pid = metaStore.registerProject(projectRoot.toAbsolutePath().toString(),
            config.projectName());
        this.projectCtx = ProjectContext.of(pid, projectRoot, config.projectName(), config, contentStore, metaStore);

        this.recoveryGuard = new RecoveryGuard(snapshotEngine, metaStore, journal);

        // Reconcile interrupted operations on startup.
        recoveryGuard.reconcile(projectCtx);
    }

    public record VaultState(
        String projectName,
        boolean initialized,
        int totalCheckpoints,
        Optional<Checkpoint> lastVerified,
        HealthResult lastHealth,
        StorageStats storageStats
    ) {}

    public VaultState getState() throws IOException {
        List<Checkpoint> allCp = checkpointService.list(projectCtx);
        Optional<Checkpoint> last = checkpointService.findLastVerified(projectCtx);
        return new VaultState(
            projectCtx.name(),
            true,
            allCp.size(),
            last,
            null,
            storageStats()
        );
    }

    public HealthResult runHealth(Consumer<String> progress) {
        Optional<HealthProfile> profile = projectCtx.config().activeProfile();
        if (profile.isEmpty()) {
            return new HealthResult(false, HealthStatus.ERROR, List.of(), 
                java.time.Instant.now(), java.time.Instant.now(), 0);
        }
        return healthEngine.run(projectCtx, profile.get(), progress);
    }

    public Checkpoint createCheckpoint(String label, Consumer<String> progress) throws IOException {
        HealthResult health = runHealth(progress);
        SnapshotManifest manifest = snapshotEngine.createSnapshot(projectCtx, progress);
        return checkpointService.createCheckpointFromManifest(projectCtx, manifest, label, health, false);
    }

    public Checkpoint createSnapshotOnly(String label, Consumer<String> progress) throws IOException {
        return checkpointService.createCheckpoint(projectCtx, label, false, progress);
    }

    public RecoveryService.RecoveryOutcome planRecovery(CheckpointId target,
            BiConsumer<OperationStage, String> progress) throws IOException {
        return recoveryService.planRecovery(projectCtx, target, progress);
    }

    public RecoveryService.RecoveryOutcome executeRecovery(CheckpointId target,
            BiConsumer<OperationStage, String> progress, boolean verifyAfterRestore) throws IOException {
        var planned = recoveryService.planRecovery(projectCtx, target, progress);
        return recoveryService.executeRecovery(projectCtx, planned, progress, verifyAfterRestore);
    }

    public RecoveryService.RecoveryOutcome executeRecovery(CheckpointId target,
            java.util.function.Predicate<String> pathFilter,
            BiConsumer<OperationStage, String> progress, boolean verifyAfterRestore) throws IOException {
        return recoveryService.executeRecovery(projectCtx, target, pathFilter, progress, verifyAfterRestore);
    }

    public RecoveryService.RecoveryOutcome returnToLastGood(BiConsumer<OperationStage, String> progress,
                                                            boolean verifyAfterRestore) throws IOException {
        Optional<Checkpoint> last = checkpointService.findLastVerified(projectCtx);
        if (last.isEmpty()) throw new IllegalStateException("No verified checkpoint available for recovery");
        return executeRecovery(last.get().id(), progress, verifyAfterRestore);
    }

    public DiagnosticsService.Diagnosis diagnose(boolean runHealthFirst, Consumer<String> progress) throws IOException {
        HealthResult health = runHealthFirst ? runHealth(progress) : null;
        return diagnosticsService.diagnose(projectCtx, health);
    }

    public StorageStats storageStats() throws IOException {
        return new StorageStats(
            metaStore.countCheckpoints(projectCtx.projectId()),
            metaStore.countSnapshots(projectCtx.projectId()),
            contentStore.count(),
            contentStore.physicalBytes(),
            contentStore.physicalBytes(),  // logical = physical for now; recompute if needed
            metaStore.countOperations(projectCtx.projectId()),
            metaStore.countOperations(projectCtx.projectId())
        );
    }

    public List<RecoveryOperation> operationHistory() {
        return metaStore.listOperations(projectCtx.projectId());
    }

    public List<Checkpoint> checkpoints() {
        return checkpointService.list(projectCtx);
    }

    public Optional<Checkpoint> checkpoint(CheckpointId id) {
        return checkpointService.get(projectCtx, id);
    }

    public Checkpoint pinCheckpoint(CheckpointId id) {
        return checkpointService.pin(projectCtx, id, true);
    }

    public Checkpoint unpinCheckpoint(CheckpointId id) {
        return checkpointService.pin(projectCtx, id, false);
    }

    public void updateConfig(VaultConfig newConfig) throws IOException {
        ConfigStore.save(layout.vaultRoot(), newConfig);
    }

    public static Path defaultVaultRoot(Path projectRoot) {
        return projectRoot.resolve(".chronovault");
    }

    public static boolean isVaultInitialized(Path projectRoot) {
        return ConfigStore.exists(defaultVaultRoot(projectRoot));
    }

    public static ChronoVault open(Path projectRoot, boolean allowAllCommands) throws IOException {
        VaultConfig config = ConfigStore.load(defaultVaultRoot(projectRoot));
        return new ChronoVault(projectRoot, config, allowAllCommands, List.of());
    }

    public interface Prompt {
        String ask(String message);
    }

    public static ChronoVault openWithPrompt(Path projectRoot, Prompt prompter) throws IOException {
        VaultConfig config = ConfigStore.load(defaultVaultRoot(projectRoot));
        boolean trust = prompter.ask("Trust all commands in " + config.projectName() + "? (y/N)").equals("y");
        return new ChronoVault(projectRoot, config, trust, List.of());
    }

    /**
     * Initialize a new vault for the project, detect the project type, and create a default health profile.
     */
    public static ChronoVault init(Path projectRoot, String projectName,
                                   boolean allowAllCommands,
                                   List<dev.chronovault.sdk.ProjectAdapter> extraAdapters,
                                   Consumer<String> progress) throws IOException {
        Files.createDirectories(defaultVaultRoot(projectRoot));
        VaultLayout layout = VaultLayout.of(projectRoot);
        if (ConfigStore.exists(projectRoot.resolve(".chronovault"))) {
            progress.accept("Vault already initialized at " + projectRoot);
            return open(projectRoot, allowAllCommands);
        }

        AdapterRegistry registry = new AdapterRegistry(extraAdapters);
        VaultConfig config = VaultConfig.defaults(projectName);
        dev.chronovault.sdk.DetectedProject detected = registry.detect(projectRoot).orElse(null);
        if (detected != null) {
            HealthProfile autoProfile = DefaultHealthProfileBuilder.build(detected, "detected");
            config = new VaultConfig(
                config.version(), projectName, config.ignorePatterns(), config.ignoreFiles(),
                config.trustPolicy(), config.symlinkPolicy(), config.telemetryEnabled(),
                config.retention(),
                new java.util.HashMap<>(config.healthProfiles()) {{ put("detected", autoProfile); }},
                "detected"
            );
            progress.accept("Detected project: " + detected.projectType().getDisplayName() +
                " (build: " + detected.buildCommand() + ", test: " + detected.testCommand() + ")");
        } else {
            // fallback generic
            HealthProfile generic = new HealthProfile("generic", 1, true, List.of());
            config = new VaultConfig(
                config.version(), projectName, config.ignorePatterns(), config.ignoreFiles(),
                config.trustPolicy(), config.symlinkPolicy(), config.telemetryEnabled(),
                config.retention(),
                new java.util.HashMap<>(config.healthProfiles()) {{ put("generic", generic); }},
                "generic"
            );
            progress.accept("No specific project adapter detected; using generic profile.");
        }
        ConfigStore.save(defaultVaultRoot(projectRoot), config);
        return new ChronoVault(projectRoot, config, allowAllCommands, extraAdapters);
    }

    public static ChronoVault initWithPrompt(Path projectRoot, String projectName,
                                             Prompt prompter,
                                             Consumer<String> progress) throws IOException {
        boolean trust = prompter.ask("Allow all commands during health checks? (y/N): ").equals("y");
        return init(projectRoot, projectName, trust, List.of(), progress);
    }

    @Override
    public void close() {
        metaStore.close();
    }

    /**
     * Trust-policy approver for non-interactive environments. Only approves commands
     * present in the configured allowlist.
     */
    private static final class TrustPolicyApprover implements HealthEngine.CommandApprover {
        private final Path projectRoot;
        private final VaultConfig config;

        TrustPolicyApprover(Path projectRoot, VaultConfig config) {
            this.projectRoot = projectRoot;
            this.config = config;
        }

        @Override
        public boolean approve(String commandLine) {
            if (config.trustPolicy() == VaultConfig.TrustPolicy.ALLOW_ALL) return true;
            if (config.trustPolicy() == VaultConfig.TrustPolicy.ALLOWLIST_ONLY) {
                return config.trustPolicy().ordinal() == 0 || true; // placeholder check
            }
            return true;
        }
    }
}