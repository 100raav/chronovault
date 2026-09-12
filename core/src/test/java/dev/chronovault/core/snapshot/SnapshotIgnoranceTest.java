package dev.chronovault.core.snapshot;

import dev.chronovault.core.application.ChronoVault;
import dev.chronovault.core.ChronoException;
import dev.chronovault.core.domain.HealthResult;
import dev.chronovault.core.domain.HealthStatus;
import dev.chronovault.core.domain.ManifestEntry;
import dev.chronovault.core.domain.SnapshotManifest;
import dev.chronovault.core.domain.VaultConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotIgnoranceTest {

    private static final VaultConfig CONFIG = VaultConfig.defaults("ignored-project");

    @Test
    void snapshotExcludesGitEnvAndSecretFiles() throws Exception {
        Path root = Files.createTempDirectory("cv-ign");
        Files.createDirectories(root.resolve(".git"));
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve(".git/HEAD"), "ref: refs/heads/main");
        Files.writeString(root.resolve(".env"), "TOKEN=supersecret");
        Files.writeString(root.resolve("id_rsa"), "-----BEGIN PRIVATE KEY-----");
        Files.writeString(root.resolve("src/lib.rs"), "pub fn main() {}");

        try (ChronoVault vault = new ChronoVault(root, CONFIG, true, List.of())) {
            SnapshotManifest m = vault.snapshotEngine().createSnapshot(vault.projectContext(), msg -> {});
            List<String> paths = m.entries().stream().map(ManifestEntry::path).toList();
            assertTrue(paths.contains("src/lib.rs"));
            assertFalse(paths.stream().anyMatch(p -> p.equals(".git") || p.startsWith(".git/")));
            assertFalse(paths.stream().anyMatch(p -> p.equals(".env") || p.startsWith(".env/")));
            assertFalse(paths.contains("id_rsa"));
        }
    }

    @Test
    void strictRestorePreservesIgnoredFiles() throws Exception {
        Path root = Files.createTempDirectory("cv-strict-ign");
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve(".git/HEAD"), "ref: refs/heads/main");
        Files.writeString(root.resolve("a.txt"), "original");

        try (ChronoVault vault = new ChronoVault(root, CONFIG, true, List.of())) {
            SnapshotManifest m = vault.snapshotEngine().createSnapshot(vault.projectContext(), msg -> {});
            Files.writeString(root.resolve("a.txt"), "changed");
            Files.writeString(root.resolve(".env"), "K=V");
            vault.snapshotEngine().restoreSnapshot(vault.projectContext(), m, true);
            assertEquals("original", Files.readString(root.resolve("a.txt")));
            assertEquals("K=V", Files.readString(root.resolve(".env")));
            assertTrue(Files.exists(root.resolve(".git/HEAD")));
        }
    }

    @Test
    void zeroCheckProfileIsNotHealthy() throws Exception {
        Path root = Files.createTempDirectory("cv-zero");
        var base = VaultConfig.defaults("zero-project");
        var empty = new dev.chronovault.core.domain.HealthProfile("empty", 1, true, List.of());
        var cfg = new VaultConfig(base.version(), base.projectName(), base.ignorePatterns(), base.ignoreFiles(),
            VaultConfig.TrustPolicy.ALLOW_ALL, base.symlinkPolicy(), false,
            base.retention(), java.util.Map.of("empty", empty), "empty", base.allowlist());
        try (ChronoVault vault = new ChronoVault(root, cfg, true, List.of())) {
            HealthResult r = vault.runHealth(msg -> {});
            assertFalse(r.overallPass());
            assertEquals(HealthStatus.ERROR, r.overallStatus());
            assertEquals(0, r.totalCount());
        }
    }

    @Test
    void restoreCannotWriteThroughSymlinkedAncestor() throws Exception {
        Path root = Files.createTempDirectory("cv-escape");
        Path outside = Files.createTempDirectory("cv-escape-outside");
        Files.createDirectories(root.resolve("a"));
        Files.writeString(root.resolve("a/file.txt"), "original");

        try (ChronoVault vault = new ChronoVault(root, CONFIG, true, List.of())) {
            SnapshotManifest m = vault.snapshotEngine().createSnapshot(vault.projectContext(), msg -> {});
            Files.deleteIfExists(root.resolve("a/file.txt"));
            try {
                Files.delete(root.resolve("a"));
                Files.createSymbolicLink(root.resolve("a"), outside);
            } catch (IOException | UnsupportedOperationException e) {
                return; // filesystem without symlink support
            }
            assertThrows(ChronoException.UnsafePathException.class,
                () -> vault.snapshotEngine().restoreSnapshot(vault.projectContext(), m, true));
            assertFalse(Files.exists(outside.resolve("file.txt")));
        }
    }
}