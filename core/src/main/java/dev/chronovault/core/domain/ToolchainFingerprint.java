package dev.chronovault.core.domain;

import java.util.Map;

public record ToolchainFingerprint(
    String fingerprint,
    Map<String, String> tools
) {}