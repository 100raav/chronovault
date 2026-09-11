package dev.chronovault.core.domain;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public record VaultConfig(
    int version,
    String projectName,
    List<String> ignorePatterns,
    List<String> ignoreFiles,
    TrustPolicy trustPolicy,
    SymlinkPolicy symlinkPolicy,
    boolean telemetryEnabled,
    RetentionPolicy retention,
    Map<String, HealthProfile> healthProfiles,
    String activeHealthProfile
) {
    public enum TrustPolicy {
        ASK, ALLOWLIST_ONLY, ALLOW_ALL
    }

    public enum SymlinkPolicy {
        FOLLOW_SAME_PROJECT, SKIP, ERROR, RESTORE_AS_LINK
    }

    public record RetentionPolicy(
        int keepLatest,
        int keepDays,
        boolean keepPinned,
        boolean keepLastHealthy
    ) {}

    public static VaultConfig defaults(String projectName) {
        return new VaultConfig(
            1,
            projectName,
            List.of(
                "build", "target", "dist", "out", "node_modules", ".venv",
                "__pycache__", ".gradle", ".idea", ".vs", "bin", "obj",
                ".pytest_cache", ".mypy_cache", ".cargo/target", "coverage",
                ".coverage", "*.class", "*.pyc", ".chronovault"
            ),
            List.of(".gitignore", ".git"),
            TrustPolicy.ASK,
            SymlinkPolicy.FOLLOW_SAME_PROJECT,
            false,
            new RetentionPolicy(50, 120, true, true),
            Map.of(),
            "default"
        );
    }

    public Optional<HealthProfile> activeProfile() {
        return Optional.ofNullable(healthProfiles.get(activeHealthProfile));
    }
}