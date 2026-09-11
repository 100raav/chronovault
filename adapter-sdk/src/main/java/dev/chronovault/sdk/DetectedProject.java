package dev.chronovault.sdk;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public record DetectedProject(
    ProjectType projectType,
    String buildCommand,
    List<String> buildArgs,
    String testCommand,
    List<String> testArgs,
    String typecheckCommand,
    List<String> typecheckArgs,
    String runtimeCommand,
    List<String> runtimeArgs,
    String packageManager,
    List<String> toolchainVersions,
    List<String> healthCheckCommands,
    Map<String, String> metadata,
    float confidence
) {
    public static DetectedProject of(ProjectType type, String buildCmd, float confidence) {
        return new DetectedProject(
            type,
            buildCmd,
            List.of(),
            null,
            List.of(),
            null,
            List.of(),
            null,
            List.of(),
            null,
            List.of(),
            List.of(),
            Map.of(),
            confidence
        );
    }

    public DetectedProject withTestCommand(String testCmd) {
        return new DetectedProject(
            projectType, buildCommand, buildArgs, testCmd, testArgs,
            typecheckCommand, typecheckArgs, runtimeCommand, runtimeArgs,
            packageManager, toolchainVersions, healthCheckCommands,
            metadata, confidence
        );
    }

    public DetectedProject withBuildCommand(String buildCmd) {
        return new DetectedProject(
            projectType, buildCmd, buildArgs, testCommand, testArgs,
            typecheckCommand, typecheckArgs, runtimeCommand, runtimeArgs,
            packageManager, toolchainVersions, healthCheckCommands,
            metadata, confidence
        );
    }

    public DetectedProject withMetadata(String key, String value) {
        Map<String, String> merged = new java.util.LinkedHashMap<>(metadata);
        merged.put(key, value);
        return new DetectedProject(
            projectType, buildCommand, buildArgs, testCommand, testArgs,
            typecheckCommand, typecheckArgs, runtimeCommand, runtimeArgs,
            packageManager, toolchainVersions, healthCheckCommands,
            merged, confidence
        );
    }
}
