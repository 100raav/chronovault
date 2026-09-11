package dev.chronovault.cli;

import dev.chronovault.core.application.ChronoVault;
import dev.chronovault.core.checkpoint.CheckpointService;
import dev.chronovault.core.domain.*;
import dev.chronovault.core.diff.ContentDiff;
import dev.chronovault.core.diff.DiffEngine;
import dev.chronovault.core.health.DiagnosticsService;
import dev.chronovault.core.recovery.RecoveryService;
import dev.chronovault.core.retention.RetentionService;
import dev.chronovault.core.snapshot.DefaultSnapshotEngine;
import dev.chronovault.core.snapshot.SnapshotEngine;
import dev.chronovault.core.util.JsonUtil;
import dev.chronovault.core.util.Sizes;
import dev.chronovault.sdk.DetectedProject;
import dev.chronovault.sdk.ProjectAdapter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

public final class Main {

    private static final String VERSION = "1.0.0";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
        .withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault());

    private static boolean jsonOutput = false;

    public static void main(String[] args) {
        if (args.length == 0) {
            usage();
            return;
        }
        String cmd = args[0];
        List<String> rest = new ArrayList<>(List.of(Arrays.copyOfRange(args, 1, args.length)));
        if (rest.contains("--json")) {
            jsonOutput = true;
            rest.remove("--json");
        }

        try {
            switch (cmd) {
                case "version", "--version", "-v" -> out("CHRONOVAULT v" + VERSION);
                case "init" -> init(parseArgs(rest));
                case "state" -> state(parseArgs(rest));
                case "health" -> health(parseArgs(rest));
                case "checkpoint" -> checkpoint(parseArgs(rest));
                case "checkpoints", "list" -> checkpoints(parseArgs(rest));
                case "restore" -> restore(parseArgs(rest));
                case "plan", "preview" -> plan(parseArgs(rest));
                case "compare", "diff" -> compare(parseArgs(rest));
                case "diagnose" -> diagnose(parseArgs(rest));
                case "history", "recovery-history" -> history(parseArgs(rest));
                case "gc" -> gc(parseArgs(rest));
                case "storage" -> storage(parseArgs(rest));
                case "pin" -> pin(parseArgs(rest), true);
                case "unpin" -> pin(parseArgs(rest), false);
                case "ui" -> ui(parseArgs(rest));
                case "detect" -> detect(parseArgs(rest));
                case "help", "--help", "-h" -> usage();
                default -> {
                    err("Unknown command: " + cmd);
                    usage();
                    System.exit(2);
                }
            }
        } catch (IllegalArgumentException e) {
            err(e.getMessage());
            System.exit(2);
        } catch (Exception e) {
            err("ERROR: " + e.getMessage());
            if (System.getenv("CHRONOVAULT_DEBUG") != null) {
                e.printStackTrace();
            }
            System.exit(1);
        }
    }

    private static void usage() {
        out("""
            CHRONOVAULT v%s — Return to the moment your code still worked.

            Usage:
              chronovault init [--name NAME] [--yes]
              chronovault detect
              chronovault state
              chronovault health
              chronovault checkpoint [--label L] [--skip-health]
              chronovault checkpoints [--limit N]
              chronovault restore [--to ID] [--preview] [--no-verify] [--yes]
              chronovault plan [--to ID]
              chronovault compare [--a ID] [--b ID] [--file PATH]
              chronovault diagnose
              chronovault history
              chronovault storage
              chronovault gc [--dry-run]
              chronovault pin ID | unpin ID
              chronovault ui [--port N] [--open]
              chronovault version
            """.formatted(VERSION));
    }

    /** Parse --key value and --flag into a map. */
    private static Map<String, String> parseArgs(List<String> args) {
        Map<String, String> map = new HashMap<>();
        String flag = null;
        for (String a : args) {
            if (a.startsWith("--")) {
                String body = a.substring(2);
                int eq = body.indexOf('=');
                if (eq > 0) {
                    if (flag != null) map.put(flag, "true");
                    map.put(body.substring(0, eq), body.substring(eq + 1));
                    flag = null;
                } else {
                    if (flag != null) map.put(flag, "true");
                    flag = body;
                }
            } else if (flag != null) {
                map.put(flag, a);
                flag = null;
            } else {
                map.put("_positional", a);
            }
        }
        if (flag != null) map.put(flag, "true");
        return map;
    }

    // ---------------------------------------------------------------- init

    private static void init(Map<String, String> args) throws Exception {
        Path root = projectRoot();
        String name = args.getOrDefault("name", root.getFileName() == null ? "project" : root.getFileName().toString());
        boolean yes = args.containsKey("yes");

        if (ChronoVault.isVaultInitialized(root)) {
            ChronoVault vault = ChronoVault.open(root, yes);
            out("Vault already initialized for " + root + "\n" +
                "  " + vault.checkpoints().size() + " checkpoints · " +
                vault.operationHistory().size() + " recoveries");
            vault.close();
            return;
        }

        ChronoVault vault = ChronoVault.init(root, name, yes, List.of(), Main::progress);
        DetectedProject detected = vault.adapterRegistry().detect(root).orElse(null);
        out(async("Temporal vault established at " + ChronoVault.defaultVaultRoot(root)));
        if (detected != null) {
            out(async("  → " + detected.projectType().getDisplayName() +
                "  build: " + detected.buildCommand() +
                (detected.testCommand() != null ? "  test: " + detected.testCommand() : "")));
        } else {
            out(async("  → generic project adapter"));
        }
        out("Run \u001b[38;5;80mchronovault checkpoint\u001b[0m to record your first verified state.");
        vault.close();
    }

    private static void detect(Map<String, String> args) throws Exception {
        Path root = projectRoot();
        boolean yes = args.containsKey("yes");
        try (ChronoVault vault = ChronoVault.open(root, yes)) {
            var detected = vault.adapterRegistry().detectAll(root);
            if (detected.isEmpty()) {
                out("No specific adapter detected at " + root);
            } else {
                for (DetectedProject d : detected) {
                    out("  " + d.projectType().getDisplayName() + " (confidence " +
                        String.format("%.0f%%", d.confidence() * 100) + ")");
                }
            }
        } catch (Exception e) {
            err("Not an initialized vault: " + e.getMessage());
            err("Run: chronovault init");
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- state

    private static void state(Map<String, String> args) throws Exception {
        Path root = projectRoot();
        boolean yes = args.containsKey("yes");
        try (ChronoVault vault = ChronoVault.open(root, yes)) {
            ChronoVault.VaultState state = vault.getState();
            List<Checkpoint> checkpoints = vault.checkpoints();
            List<RecoveryOperation> ops = vault.operationHistory();
            Checkpoint lastVerified = state.lastVerified().orElse(null);

            if (jsonOutput) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("project", root.toString());
                map.put("name", state.projectName());
                map.put("checkpoints", checkpoints.size());
                map.put("recoveries", ops.size());
                map.put("storage", state.storageStats());
                map.put("lastVerified", lastVerified != null ? lastVerified.id().value() : null);
                out(JsonUtil.toJson(map));
                return;
            }

            out(cyan("◉ CHRONOVAULT") + " · " + state.projectName() + " · " + root);
            out("  Checkpoints     : " + state.totalCheckpoints());
            out("  Recoveries      : " + ops.size());
            out("  Last verified   : " + (lastVerified != null
                ? checkpointLabel(lastVerified) : amber("none yet")));
            out("  Status          : " + overallStatus(vault, lastVerified));
            out("");
            out("  Storage         : " + Sizes.human(state.storageStats().physicalBytes()) +
                " physical · " + state.storageStats().objects() + " objects · " +
                String.format("%.0f%% dedup", state.storageStats().deduplicationRatio() * 100));
        }
    }

    private static String overallStatus(ChronoVault vault, Checkpoint lastVerified) {
        if (lastVerified == null) return amber("UNPROTECTED — no verified state yet");
        return green("PROTECTED — " + lastVerified.id() + " verified at " + TIME.format(lastVerified.createdAt()));
    }

    // ---------------------------------------------------------------- health

    private static void health(Map<String, String> args) throws Exception {
        Path root = projectRoot();
        boolean yes = args.containsKey("yes");
        try (ChronoVault vault = ChronoVault.open(root, yes)) {
            HealthResult result = vault.runHealth(Main::progress);
            Checkpoint latest = vault.checkpoints().stream().findFirst().orElse(null);
            reportHealth(result, latest);
            if (jsonOutput) {
                out(JsonUtil.toJson(result));
            }
        }
    }

    private static void reportHealth(HealthResult result, Checkpoint latest) {
        boolean pass = result.overallPass();
        out((pass ? green("✓") : red("✗")) + " HEALTH " + (pass ? green("PASS") : red("FAIL")) +
            "  ·  " + result.passedCount() + "/" + result.totalCount() + " checks · " +
            String.format("%.1fs", result.totalDurationMs() / 1000.0));
        for (HealthCheckResult c : result.checks()) {
            String icon = switch (c.status()) {
                case PASS -> green("✓");
                case FAIL -> red("✗");
                case ERROR -> amber("!");
                case SKIPPED -> dim("–");
            };
            out("  " + icon + " " + c.name() + (c.status() == HealthStatus.FAIL && !c.errorTail().isBlank()
                ? dim(" — " + oneLine(c.errorTail())) : ""));
        }
    }

    // ---------------------------------------------------------------- checkpoint

    private static void checkpoint(Map<String, String> args) throws Exception {
        Path root = projectRoot();
        boolean yes = args.containsKey("yes");
        String label = args.getOrDefault("label", null);
        boolean skipHealth = args.containsKey("skip-health");

        try (ChronoVault vault = ChronoVault.open(root, yes)) {
            Consumer2 progress = new Consumer2();
            if (skipHealth) {
                Checkpoint cp = vault.createSnapshotOnly(label, progress::accept);
                out(async("Checkpoint created " + cyan(cp.id().value()) + " — " +
                    (cp.status() == CheckpointStatus.VERIFIED ? green("VERIFIED") : amber("UNVERIFIED"))));
                if (jsonOutput) out(JsonUtil.toJson(cp));
                return;
            }
            out(async("Verifying project…"));
            HealthResult health = vault.runHealth(Main::progress);
            String diagnosis = health.overallPass() ? green("HEALTHY") : red("BROKEN");
            out(async("Project " + diagnosis + " — " + health.passedCount() + "/" + health.totalCount() + " checks"));
            Checkpoint cp = vault.createSnapshotOnly(label, progress::accept);
            if (health.overallPass()) {
                vault.checkpointService().setStatus(vault.projectContext(), cp.id(), CheckpointStatus.VERIFIED);
                out(green("✓ CHECKPOINT " + cp.id() + " VERIFIED HEALTHY"));
                out(green("  " + health.passedCount() + "/" + health.totalCount() + " checks passed"));
            } else {
                vault.checkpointService().setStatus(vault.projectContext(), cp.id(), CheckpointStatus.BROKEN);
                out(red("✗ CHECKPOINT " + cp.id() + " RECORDED BROKEN"));
                out(red("  Checkpoint is not eligible for recovery."));
            }
            if (jsonOutput) out(JsonUtil.toJson(cp));
        }
    }

    private static final class Consumer2 {
        void accept(String s) {}
    }

    // ---------------------------------------------------------------- checkpoints

    private static void checkpoints(Map<String, String> args) throws Exception {
        Path root = projectRoot();
        boolean yes = args.containsKey("yes");
        int limit = Integer.parseInt(args.getOrDefault("limit", "20"));
        try (ChronoVault vault = ChronoVault.open(root, yes)) {
            List<Checkpoint> cps = vault.checkpointService().list(vault.projectContext(), limit);
            if (jsonOutput) {
                out(JsonUtil.toJson(cps));
                return;
            }
            if (cps.isEmpty()) {
                out(amber("No checkpoints yet. Run `chronovault checkpoint` to create your first one."));
                return;
            }
            for (Checkpoint cp : cps) {
                String status = switch (cp.status()) {
                    case VERIFIED -> green("✓ VERIFIED");
                    case BROKEN -> red("✗ BROKEN");
                    case ACTIVE -> cyan("◉ ACTIVE");
                    default -> dim(cp.status().name());
                };
                String health = cp.evidence() != null && cp.evidence().healthResult() != null
                    ? "  " + cp.evidence().healthResult().passedCount() + "/" + cp.evidence().healthResult().totalCount() + " checks"
                    : "";
                out("  " + cp.id() + "  " + status + health + "  " + TIME.format(cp.createdAt()));
            }
        }
    }

    // ---------------------------------------------------------------- restore

    private static void restore(Map<String, String> args) throws Exception {
        Path root = projectRoot();
        boolean yes = args.containsKey("yes");
        try (ChronoVault vault = ChronoVault.open(root, yes)) {
            Checkpoint target;
            if (args.containsKey("to")) {
                target = vault.checkpointService().get(vault.projectContext(), CheckpointId.of(args.get("to")))
                    .orElseThrow(() -> new IllegalArgumentException("Checkpoint not found: " + args.get("to")));
            } else {
                target = vault.checkpointService().findLastVerified(vault.projectContext())
                    .orElseThrow(() -> new IllegalArgumentException(
                        "No verified checkpoint found. Run `chronovault health` to verify the project, then `chronovault checkpoint`."));
            }
            if (target.status() != CheckpointStatus.VERIFIED && target.status() != CheckpointStatus.ACTIVE) {
                throw new IllegalArgumentException("Recovery target " + target.id() + " is not verified (" + target.status() + ").");
            }

            if (args.containsKey("preview")) {
                plan(args);
                return;
            }

            if (!yes && !confirm("Restore project to " + target.id() + "? Current work will be protected. (y/N) ")) {
                out("Aborted.");
                return;
            }

            boolean verify = !args.containsKey("no-verify");
            out(async("Temporal trace → " + cyan(target.id().value())));
            RecoveryService.RecoveryOutcome outcome;
            if (args.containsKey("only")) {
                String[] sel = args.get("only").split(",");
                outcome = vault.executeRecovery(target.id(),
                    p -> {
                        for (String s : sel) {
                            if (s.isBlank()) continue;
                            if (p.equals(s) || p.startsWith(s + "/")) return true;
                        }
                        return false;
                    },
                    (stage, msg) -> printStage(stage, msg),
                    verify);
            } else {
                outcome = vault.executeRecovery(target.id(),
                    (stage, msg) -> printStage(stage, msg),
                    verify);
            }

            switch (outcome.finalStage()) {
                case COMPLETED -> {
                    out(green("✓ STATE RESTORED"));
                    Checkpoint cp = vault.checkpoint(target.id()).orElse(target);
                    if (cp.evidence() != null && cp.evidence().healthResult() != null) {
                        out(green("  " + cp.evidence().healthResult().passedCount() + "/" +
                            cp.evidence().healthResult().totalCount() + " TESTS PASSED"));
                    }
                    out(green("  " + target.id() + " is now your active state"));
                }
                case ROLLED_BACK -> {
                    out(amber("✗ RECOVERY FAILED — verification did not pass"));
                    out(green("✓ Rollback complete — your pre-recovery state has been restored"));
                }
                default -> {
                    out(red("✗ RECOVERY FAILED — " + outcome.message()));
                }
            }
            if (jsonOutput) out(JsonUtil.toJson(outcome));
        }
    }

    private static void plan(Map<String, String> args) throws Exception {
        Path root = projectRoot();
        boolean yes = args.containsKey("yes");
        try (ChronoVault vault = ChronoVault.open(root, yes)) {
            Checkpoint target;
            if (args.containsKey("to")) {
                target = vault.checkpointService().get(vault.projectContext(), CheckpointId.of(args.get("to")))
                    .orElseThrow(() -> new IllegalArgumentException("Checkpoint not found: " + args.get("to")));
            } else {
                target = vault.checkpointService().findLastVerified(vault.projectContext())
                    .orElseThrow(() -> new IllegalArgumentException("No verified checkpoint found."));
            }
            RecoveryService.RecoveryOutcome planned = vault.recoveryService().planRecovery(
                vault.projectContext(), target.id(), (s, m) -> {});
            RestorePlan plan = planned.plan();
            if (jsonOutput) {
                out(JsonUtil.toJson(plan));
                return;
            }
            out(cyan("RECOVERY PREVIEW — return to " + target.id()));
            out("  " + plan.modifiedFiles() + " modified · " + plan.addedFiles() + " added · " +
                plan.deletedFiles() + " deleted · " + plan.renamedFiles() + " renamed");
            out("  " + Sizes.human(plan.totalBytes()) + " to restore · current work will be protected");
            out("  Run `chronovault restore --to " + target.id().value() + "` to execute.");
        }
    }

    // ---------------------------------------------------------------- compare

    private static void compare(Map<String, String> args) throws Exception {
        Path root = projectRoot();
        boolean yes = args.containsKey("yes");
        try (ChronoVault vault = ChronoVault.open(root, yes)) {
            List<Checkpoint> cps = vault.checkpoints();
            Checkpoint cpA = args.containsKey("a")
                ? byId(cps, args.get("a")).orElseThrow(() -> new IllegalArgumentException("Unknown checkpoint: " + args.get("a")))
                : cps.stream().filter(c -> c.evidence() != null && c.evidence().passed())
                    .findFirst().orElse(cps.get(0));
            Checkpoint cpB = args.containsKey("b")
                ? byId(cps, args.get("b")).orElseThrow(() -> new IllegalArgumentException("Unknown checkpoint: " + args.get("b")))
                : cps.get(0);

            var manifestA = vault.metaStore().getSnapshotManifest(vault.projectContext().projectId(), cpA.snapshotId())
                .orElseThrow(() -> new IllegalStateException("Snapshot for " + cpA.id() + " missing"));
            var manifestB = vault.metaStore().getSnapshotManifest(vault.projectContext().projectId(), cpB.snapshotId())
                .orElseThrow(() -> new IllegalStateException("Snapshot for " + cpB.id() + " missing"));

            DiffEngine.DiffResult diff = vault.diffEngine().diff(manifestA.entries(), manifestB.entries());

            if (jsonOutput) {
                out(JsonUtil.toJson(diff));
                return;
            }

            out(cyan("COMPARE " + cpA.id() + " ↔ " + cpB.id()));
            out("  " + diff.total() + " file changes: " + diff.added() + " added, " +
                diff.modified() + " modified, " + diff.deleted() + " deleted, " + diff.renamed() + " renamed");
            for (DiffEngine.Change c : diff.changes().stream().limit(20).toList()) {
                String icon = switch (c.kind()) {
                    case ADDED -> green("+");
                    case DELETED -> red("−");
                    case MODIFIED -> amber("~");
                    case RENAMED -> cyan("→");
                    case SYMLINK -> dim("⊂");
                };
                out("  " + icon + " " + c.path());
            }
            if (diff.changes().size() > 20) {
                out(dim("  … and " + (diff.changes().size() - 20) + " more"));
            }
        }
    }

    private static Optional<Checkpoint> byId(List<Checkpoint> cps, String id) {
        return cps.stream().filter(c -> c.id().value().equals(id) || c.shortId().equals(id)).findFirst();
    }

    // ---------------------------------------------------------------- diagnose

    private static void diagnose(Map<String, String> args) throws Exception {
        Path root = projectRoot();
        boolean yes = args.containsKey("yes");
        try (ChronoVault vault = ChronoVault.open(root, yes)) {
            DiagnosticsService.Diagnosis diag = vault.diagnose(true, Main::progress);
            if (jsonOutput) {
                out(JsonUtil.toJson(diag));
                return;
            }
            if (diag.lastHealthyCheckpoint() == null) {
                out(amber("WHAT BROKE IT? — No verified checkpoint yet."));
                out("  Create a verified checkpoint to enable diagnosis.");
                return;
            }
            out(cyan("WHAT BROKE IT?"));
            out("  Last healthy   : " + diag.lastHealthyCheckpoint() + " at " + TIME.format(diag.lastHealthyAt()));
            out("  Changes since  : " + diag.changesSinceHealthy());
            for (DiagnosticsService.EvidenceCard card : diag.cards()) {
                String tag = switch (card.kind()) {
                    case "FACT" -> cyan("FACT");
                    case "OBSERVATION" -> amber("OBS");
                    default -> violet("HYP");
                };
                out("  [" + tag + "] " + (switch (card.severity()) {
                    case "ERROR" -> red(card.title());
                    case "WARN" -> amber(card.title());
                    default -> card.title();
                }));
                if (!card.detail().isBlank() && card.kind().equals("HYPOTHESIS")) {
                    out(dim("         " + card.detail()));
                }
            }
        }
    }

    // ---------------------------------------------------------------- history

    private static void history(Map<String, String> args) throws Exception {
        Path root = projectRoot();
        boolean yes = args.containsKey("yes");
        try (ChronoVault vault = ChronoVault.open(root, yes)) {
            List<RecoveryOperation> ops = vault.operationHistory();
            if (jsonOutput) {
                out(JsonUtil.toJson(ops));
                return;
            }
            if (ops.isEmpty()) {
                out(amber("No recovery operations yet."));
                return;
            }
            out(cyan("RECOVERY HISTORY"));
            for (RecoveryOperation op : ops) {
                String icon = switch (op.stage()) {
                    case COMPLETED -> green("✓");
                    case ROLLED_BACK, FAILED -> amber("✗");
                    default -> dim("•");
                };
                out("  " + icon + " " + op.operationId() + "  " + op.stage() +
                    (op.targetCheckpoint() != null ? "  → " + op.targetCheckpoint() : "") +
                    "  " + TIME.format(op.createdAt()));
            }
        }
    }

    // ---------------------------------------------------------------- gc / storage

    private static void gc(Map<String, String> args) throws Exception {
        Path root = projectRoot();
        boolean yes = args.containsKey("yes");
        try (ChronoVault vault = ChronoVault.open(root, yes)) {
            RetentionService.RetentionReport report = vault.retentionService().applyPolicy(
                vault.projectContext(), vault.projectContext().config().retention(),
                args.containsKey("dry-run"));
            out(amber("RETENTION + GARBAGE COLLECTION"));
            out("  checkpoints removed : " + report.checkpointsRemoved());
            out("  snapshots removed   : " + report.snapshotsRemoved());
            out("  objects removed     : " + report.objectsRemoved());
            out("  bytes freed         : " + Sizes.human(report.bytesFreed()));
        }
    }

    private static void storage(Map<String, String> args) throws Exception {
        Path root = projectRoot();
        boolean yes = args.containsKey("yes");
        try (ChronoVault vault = ChronoVault.open(root, yes)) {
            StorageStats s = vault.storageStats();
            if (jsonOutput) {
                out(JsonUtil.toJson(s));
                return;
            }
            out(cyan("CHRONOVAULT VAULT · " + root));
            out("  Checkpoints    : " + s.checkpoints());
            out("  Snapshots      : " + s.snapshots());
            out("  Objects        : " + s.objects());
            out("  Physical       : " + Sizes.human(s.physicalBytes()));
            out("  Deduplication  : " + String.format("%.0f%%", s.deduplicationRatio() * 100));
            out("  Recoveries     : " + s.recoveryHistory());
        }
    }

    private static void pin(Map<String, String> args, boolean pinned) throws Exception {
        Path root = projectRoot();
        boolean yes = args.containsKey("yes");
        String id = args.get("_positional");
        if (id == null) id = args.getOrDefault("id", args.values().stream()
            .filter(v -> v.startsWith("cp-")).findFirst().orElse(null));
        if (id == null) throw new IllegalArgumentException("Specify a checkpoint id");
        try (ChronoVault vault = ChronoVault.open(root, yes)) {
            Checkpoint cp = vault.pinCheckpoint(CheckpointId.of(id));
            out((pinned ? green("✓ pinned ") : "unpinned ") + cp.id().value());
        }
    }

    // ---------------------------------------------------------------- ui

    private static void ui(Map<String, String> args) throws Exception {
        int port = Integer.parseInt(args.getOrDefault("port", "7723"));
        boolean open = args.containsKey("open");
        Path root = projectRoot();
        try (ChronoVault vault = ChronoVault.open(root, true)) {
            ChronoServer server = new ChronoServer(vault, port);
            server.start();
            out(cyan("CHRONOVAULT UI") + " → http://localhost:" + port);
            out("  Ctrl+C to stop. (--open to auto-open browser)");
            if (open) {
                try {
                    Runtime.getRuntime().exec(new String[]{"open", "http://localhost:" + port});
                } catch (Exception ignored) {}
            }
            Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
            Thread.currentThread().join();
        }
    }

    // ---------------------------------------------------------------- helpers

    public static Path projectRoot() {
        String env = System.getenv("CHRONOVAULT_PROJECT");
        Path root = env != null && !env.isBlank() ? Path.of(env) : Paths.get("").toAbsolutePath();
        root = root.toAbsolutePath().normalize();
        try {
            root = root.toRealPath();
        } catch (IOException ignored) {
            // path may not exist yet; fall back to the normalized absolute path
        }
        return root;
    }

    private static boolean confirm(String msg) {
        System.out.print(msg);
        try {
            byte[] buf = new byte[16];
            int n = System.in.read(buf);
            return n > 0 && new String(buf, 0, n, StandardCharsets.UTF_8).trim().equalsIgnoreCase("y");
        } catch (Exception e) {
            return false;
        }
    }

    private static String checkpointLabel(Checkpoint cp) {
        return cyan(cp.id().value()) + " @" + TIME.format(cp.createdAt());
    }

    public static void printStage(OperationStage stage, String msg) {
        String label = switch (stage) {
            case PLANNED -> "PLANNING";
            case PROTECTING -> "PROTECTING CURRENT STATE";
            case PROTECTED -> "PROTECTED";
            case RESTORING -> "RESTORING FILES";
            case VERIFYING -> "VERIFYING";
            case COMMITTING -> "COMMITTING";
            case COMPLETED -> "RECOVERED";
            case ROLLING_BACK -> "ROLLING BACK";
            case ROLLED_BACK -> "ROLLED BACK";
            case FAILED -> "FAILED";
            default -> stage.name();
        };
        System.err.println("  ▸ " + label + (msg != null && !msg.isBlank() ? " — " + msg : ""));
    }

    public static void progress(String msg) {
        System.err.println(dim("  " + msg));
    }

    private static String oneLine(String s) {
        if (s == null || s.isBlank()) return "";
        String line = s.lines().map(String::trim).filter(l -> !l.isEmpty()).findFirst().orElse("");
        if (line.length() > 140) line = line.substring(0, 140) + "…";
        return line;
    }

    private static String green(String s) { return "\u001b[38;5;83m" + s + "\u001b[0m"; }
    private static String red(String s) { return "\u001b[38;5;203m" + s + "\u001b[0m"; }
    private static String amber(String s) { return "\u001b[38;5;215m" + s + "\u001b[0m"; }
    private static String cyan(String s) { return "\u001b[38;5;80m" + s + "\u001b[0m"; }
    private static String violet(String s) { return "\u001b[38;5;141m" + s + "\u001b[0m"; }
    private static String dim(String s) { return "\u001b[38;5;244m" + s + "\u001b[0m"; }
    private static String async(String s) { return dim("⟐ ") + s; }

    private static void out(String s) { System.out.println(s); }
    private static void err(String s) { System.err.println(s); }
}