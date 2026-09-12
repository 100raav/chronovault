package dev.chronovault.core.health;

import dev.chronovault.core.domain.*;
import dev.chronovault.core.project.ProjectContext;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class HealthEngine {
    private final CommandExecutor executor;
    private final CommandApprover approver;

    public interface CommandApprover {
        boolean approve(String commandLine);
    }

    public HealthEngine(CommandExecutor executor, CommandApprover approver) {
        this.executor = executor;
        this.approver = approver;
    }

    public HealthResult run(ProjectContext ctx, HealthProfile profile,
                            Consumer<String> progressCallback) {
        Instant startedAt = Instant.now();
        List<HealthCheckResult> results = new ArrayList<>();
        boolean overAllPass = true;

        if (profile.checks().isEmpty()) {
            Instant finishedAt = Instant.now();
            return new HealthResult(false, HealthStatus.ERROR, List.of(),
                startedAt, finishedAt, finishedAt.toEpochMilli() - startedAt.toEpochMilli());
        }

        for (HealthProfile.HealthCheckDefinition check : profile.checks()) {
            String cmdLine = String.join(" ", check.command());
            if (!approver.approve(cmdLine)) {
                results.add(new HealthCheckResult(check.id(), check.name(), check.kind(),
                    HealthStatus.ERROR, check.required(), -1, 0,
                    "", "Command not approved by trust policy: " + cmdLine));
                overAllPass = false;
                if (profile.failFast() && check.required()) break;
                continue;
            }

            Path workDir = check.workingSubdir() == null || check.workingSubdir().isBlank()
                ? ctx.root()
                : ctx.root().resolve(check.workingSubdir());

            if (progressCallback != null) progressCallback.accept("Running " + check.name() + "…");

            HealthStatus status;
            CommandExecutor.CommandResult res = null;
            try {
                res = executor.execute(workDir, check.command(),
                    Duration.ofSeconds(check.timeoutSeconds()));
            } catch (IOException e) {
                status = HealthStatus.ERROR;
            }

            if (res == null) {
                results.add(new HealthCheckResult(check.id(), check.name(), check.kind(),
                    HealthStatus.ERROR, check.required(), -1, 0, "", "Command could not be executed"));
                overAllPass = false;
                if (profile.failFast() && check.required()) break;
                continue;
            }

            if (res.success()) {
                status = HealthStatus.PASS;
            } else if (res.timedOut() || res.wasCancelled()) {
                status = HealthStatus.ERROR;
            } else {
                status = HealthStatus.FAIL;
            }

            results.add(new HealthCheckResult(check.id(), check.name(), check.kind(),
                status, check.required(), res.exitCode(), res.durationMs(),
                tail(res.stdout()), tail(res.stderr())));

            if (status != HealthStatus.PASS) {
                overAllPass = false;
                if (profile.failFast() && check.required()) break;
            }
        }

        Instant finishedAt = Instant.now();
        HealthStatus overall = overAllPass ? HealthStatus.PASS : HealthStatus.FAIL;
        return new HealthResult(overAllPass, overall, List.copyOf(results),
            startedAt, finishedAt, finishedAt.toEpochMilli() - startedAt.toEpochMilli());
    }

    private String tail(String s) {
        if (s == null || s.isBlank()) return "";
        if (s.length() <= 4000) return s;
        return "…" + s.substring(s.length() - 4000);
    }
}