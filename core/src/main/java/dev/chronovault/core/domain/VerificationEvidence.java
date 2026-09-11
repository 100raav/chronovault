package dev.chronovault.core.domain;

public record VerificationEvidence(
    String healthProfileName,
    int healthProfileVersion,
    HealthResult healthResult,
    ToolchainFingerprint toolchain,
    DependencyFingerprint dependencies,
    EnvironmentFingerprint environment
) {
    public boolean passed() {
        return healthResult != null && healthResult.overallPass();
    }
}