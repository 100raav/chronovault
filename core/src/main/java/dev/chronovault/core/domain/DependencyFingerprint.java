package dev.chronovault.core.domain;

import java.util.Map;

public record DependencyFingerprint(
    String fingerprint,
    Map<String, String> manifests
) {}