package dev.chronovault.core.application;

import dev.chronovault.core.domain.HealthProfile;
import dev.chronovault.core.domain.HealthResult;
import dev.chronovault.core.domain.HealthStatus;
import dev.chronovault.core.domain.VaultConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChronoVaultTrustTest {

    private static final String ECHO_OK = "echo ok";

    private static VaultConfig config(VaultConfig.TrustPolicy policy, List<String> allowlist) {
        VaultConfig base = VaultConfig.defaults("trusted-project");
        HealthProfile profile = new HealthProfile("test", 1, true, List.of(
            new HealthProfile.HealthCheckDefinition("echo", "Echo", "BUILD",
                List.of("echo", "ok"), true, 30, ".", false)));
        return new VaultConfig(base.version(), base.projectName(), base.ignorePatterns(), base.ignoreFiles(),
            policy, base.symlinkPolicy(), false, base.retention(),
            Map.of("test", profile), "test", allowlist);
    }

    @Test
    void allowlistOnlyRejectsCommandsNotInAllowlist() throws Exception {
        Path root = Files.createTempDirectory("cv-trust-empty");
        try (ChronoVault vault = new ChronoVault(root, config(VaultConfig.TrustPolicy.ALLOWLIST_ONLY, List.of()), false, List.of())) {
            HealthResult r = vault.runHealth(msg -> {});
            assertFalse(r.overallPass());
            assertEquals(HealthStatus.FAIL, r.overallStatus());
            assertEquals(1, r.checks().size());
            assertEquals(HealthStatus.ERROR, r.checks().get(0).status());
            assertTrue(r.checks().get(0).errorTail().contains("not approved"));
        }
    }

    @Test
    void allowlistOnlyRunsAllowlistedCommand() throws Exception {
        Path root = Files.createTempDirectory("cv-trust-allowed");
        try (ChronoVault vault = new ChronoVault(root, config(VaultConfig.TrustPolicy.ALLOWLIST_ONLY, List.of(ECHO_OK)), false, List.of())) {
            HealthResult r = vault.runHealth(msg -> {});
            assertTrue(r.overallPass());
            assertEquals(HealthStatus.PASS, r.checks().get(0).status());
        }
    }

    @Test
    void allowAllApprovesRegardlessOfAllowlist() throws Exception {
        Path root = Files.createTempDirectory("cv-trust-allowall");
        try (ChronoVault vault = new ChronoVault(root, config(VaultConfig.TrustPolicy.ALLOW_ALL, List.of()), false, List.of())) {
            HealthResult r = vault.runHealth(msg -> {});
            assertTrue(r.overallPass());
            assertEquals(HealthStatus.PASS, r.checks().get(0).status());
        }
    }
}