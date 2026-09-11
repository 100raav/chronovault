package dev.chronovault.core.recovery;

import dev.chronovault.core.application.ChronoVault;
import dev.chronovault.core.domain.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Test fixture wiring a real ChronoVault instance rooted in a temp directory
 * with a configurable health profile. Used across core subsystem tests.
 */
public final class TestVaultHarness implements AutoCloseable {
    public final Path root;
    public final ChronoVault vault;

    public TestVaultHarness(Path root, HealthProfile profile) throws Exception {
        this.root = root;
        VaultConfig config = VaultConfig.defaults("test-project");
        config = new VaultConfig(
            config.version(), config.projectName(), config.ignorePatterns(), config.ignoreFiles(),
            VaultConfig.TrustPolicy.ALLOW_ALL, config.symlinkPolicy(), false,
            config.retention(), java.util.Map.of("test", profile), "test");
        this.vault = new ChronoVault(root, config, true, List.of());
    }

    public static Path file(Path root, String rel) throws Exception {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        return p;
    }

    public static void write(Path root, String rel, String content) throws Exception {
        Path p = file(root, rel);
        Files.writeString(p, content);
    }

    public static HealthProfile alwaysPass(String name) {
        return new HealthProfile(name, 1, true, List.of(
            new HealthProfile.HealthCheckDefinition("checkpoint", "Echo check", "BUILD",
                List.of("true"), true, 30, ".", false)
        ));
    }

    public static HealthProfile runJs(String name, String code) {
        return new HealthProfile(name, 1, true, List.of(
            new HealthProfile.HealthCheckDefinition("js", "Node check", "BUILD",
                List.of("node", "-e", code), true, 60, ".", false)
        ));
    }

    @Override
    public void close() {
        vault.close();
    }
}