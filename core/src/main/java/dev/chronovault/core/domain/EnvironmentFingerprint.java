package dev.chronovault.core.domain;

import java.util.Map;

public record EnvironmentFingerprint(
    String os,
    String arch,
    String javaVersion,
    Map<String, String> tools
) {}