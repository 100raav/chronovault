package dev.chronovault.core.health;

import dev.chronovault.core.domain.*;
import dev.chronovault.core.project.ProjectContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HealthEngineTest {

    @TempDir Path tempDir;

    @Test
    void runsRealCommandAndRecordsEvidence() throws Exception {
        Path root = tempDir.resolve("proj");
        Files.createDirectories(root);
        Files.writeString(root.resolve("payload.txt"), "hello");

        HealthEngine engine = new HealthEngine(new ProcessCommandExecutor(), cmd -> true);
        HealthProfile profile = new HealthProfile("p", 1, true, List.of(
            new HealthProfile.HealthCheckDefinition("echo", "Echo", "BUILD",
                List.of("cat", "payload.txt"), true, 30, ".", false)
        ));

        ProjectContext ctx = ProjectContext.of(ProjectId.of("p1"), root, "t", VaultConfig.defaults("t"),
            null, null);

        HealthResult result = engine.run(ctx, profile, null);

        assertTrue(result.overallPass());
        assertEquals(1, result.checks().size());
        HealthCheckResult check = result.checks().get(0);
        assertEquals(HealthStatus.PASS, check.status());
        assertEquals(0, check.exitCode());
        assertTrue(check.durationMs() >= 0);
        assertTrue(check.outputTail().contains("hello"));
    }

    @Test
    void failingCommandProducesFailStatus() throws Exception {
        Path root = tempDir.resolve("proj");
        Files.createDirectories(root);
        HealthEngine engine = new HealthEngine(new ProcessCommandExecutor(), cmd -> true);
        HealthProfile profile = new HealthProfile("p", 1, true, List.of(
            new HealthProfile.HealthCheckDefinition("cd", "Falsey", "BUILD",
                List.of("false"), true, 30, ".", false)
        ));
        ProjectContext ctx = ProjectContext.of(ProjectId.of("p1"), root, "t", VaultConfig.defaults("t"),
            null, null);
        HealthResult result = engine.run(ctx, profile, null);
        assertFalse(result.overallPass());
        assertEquals(HealthStatus.FAIL, result.checks().get(0).status());
        assertTrue(result.checks().get(0).exitCode() != 0, "fail exit code must be recorded");
    }

    @Test
    void timeoutTerminatesProcess() throws Exception {
        Path root = tempDir.resolve("proj");
        Files.createDirectories(root);
        HealthEngine engine = new HealthEngine(new ProcessCommandExecutor(), cmd -> true);
        HealthProfile profile = new HealthProfile("p", 1, true, List.of(
            new HealthProfile.HealthCheckDefinition("sleep", "Sleep", "CUSTOM",
                List.of("sleep", "30"), false, 2, ".", false)
        ));
        ProjectContext ctx = ProjectContext.of(ProjectId.of("p1"), root, "t", VaultConfig.defaults("t"),
            null, null);
        long start = System.nanoTime();
        HealthResult result = engine.run(ctx, profile, null);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs < 15000, "timeout must fire quickly, took " + elapsedMs + "ms");
        assertFalse(result.overallPass());
    }

    @Test
    void trustPolicyBlocksDisallowedCommand() throws Exception {
        Path root = tempDir.resolve("proj");
        Files.createDirectories(root);
        HealthEngine engine = new HealthEngine(new ProcessCommandExecutor(), cmd ->
            cmd.startsWith("make") || cmd.startsWith("mvn"));
        HealthProfile profile = new HealthProfile("p", 1, true, List.of(
            new HealthProfile.HealthCheckDefinition("evil", "Evil", "CUSTOM",
                List.of("rm", "-rf", "/tmp/whatever"), false, 30, ".", false)
        ));
        ProjectContext ctx = ProjectContext.of(ProjectId.of("p1"), root, "t", VaultConfig.defaults("t"),
            null, null);
        HealthResult result = engine.run(ctx, profile, null);
        assertEquals(HealthStatus.ERROR, result.checks().get(0).status());
        assertFalse(result.overallPass());
        // Command should never have executed:
        assertFalse(Files.exists(Path.of("/tmp/whatever")));
    }

    @Test
    void outputIsScrubbedOfSecrets() throws Exception {
        SecretScrubber scrubber = new SecretScrubber();
        String in = "connecting with password=SuperSecret123 and token: abcdefghijkl\n"; 
        String out = scrubber.scrub(in);
        assertFalse(out.contains("SuperSecret123"));
        assertTrue(out.contains("[REDACTED]"));
    }
}