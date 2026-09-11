package dev.chronovault.adapters.generic;

import dev.chronovault.sdk.DetectedProject;
import dev.chronovault.sdk.ProjectAdapter;
import dev.chronovault.sdk.ProjectType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public final class GenericAdapter implements ProjectAdapter {
    @Override
    public String id() { return "generic"; }

    @Override
    public String displayName() { return "Generic Project"; }

    @Override
    public List<ProjectType> supportedTypes() { return List.of(ProjectType.GENERIC); }

    @Override
    public Optional<DetectedProject> detect(Path projectRoot) {
        boolean hasMakefile = Files.exists(projectRoot.resolve("Makefile"));
        boolean hasSrc = Files.exists(projectRoot.resolve("src"));
        if (hasMakefile || hasSrc) {
            return Optional.of(DetectedProject.of(ProjectType.GENERIC,
                hasMakefile ? "make" : null, hasMakefile ? 0.5f : 0.2f));
        }
        return Optional.empty();
    }
}