package dev.chronovault.core.health;

import dev.chronovault.core.domain.HealthProfile;
import dev.chronovault.sdk.DetectedProject;
import dev.chronovault.sdk.ProjectType;

import java.util.ArrayList;
import java.util.List;

public final class DefaultHealthProfileBuilder {

    public static HealthProfile build(DetectedProject project, String profileName) {
        List<HealthProfile.HealthCheckDefinition> checks = new ArrayList<>();

        if (project.buildCommand() != null && !project.buildCommand().isBlank()) {
            checks.add(new HealthProfile.HealthCheckDefinition(
                "build", "Build", "BUILD",
                appendArgs(project.buildCommand(), project.buildArgs()),
                true, 600, ".", false));
        }
        if (project.testCommand() != null && !project.testCommand().isBlank()) {
            checks.add(new HealthProfile.HealthCheckDefinition(
                "test", "Tests", "TEST",
                appendArgs(project.testCommand(), project.testArgs()),
                true, 600, ".", false));
        }
        if (project.typecheckCommand() != null && !project.typecheckCommand().isBlank()) {
            checks.add(new HealthProfile.HealthCheckDefinition(
                "typecheck", "Type check", "TYPECHECK",
                appendArgs(project.typecheckCommand(), project.typecheckArgs()),
                false, 300, ".", false));
        }
        for (String cmd : project.healthCheckCommands()) {
            checks.add(new HealthProfile.HealthCheckDefinition(
                "custom_" + (checks.size() + 1), cmd, "CUSTOM",
                List.of(cmd.split("\\s+")), false, 120, ".", false));
        }

        return new HealthProfile(profileName, 1, true, checks);
    }

    private static List<String> appendArgs(String cmd, List<String> args) {
        List<String> out = new ArrayList<>(List.of(cmd.split("\\s+")));
        if (args != null) out.addAll(args);
        return out;
    }
}