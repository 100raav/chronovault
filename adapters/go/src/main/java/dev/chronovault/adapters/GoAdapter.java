package dev.chronovault.adapters.go;

import dev.chronovault.sdk.DetectedProject;
import dev.chronovault.sdk.ProjectAdapter;
import dev.chronovault.sdk.ProjectType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public final class GoAdapter implements ProjectAdapter {
    @Override public String id() { return "go"; }
    @Override public String displayName() { return "Go"; }
    @Override public List<ProjectType> supportedTypes() { return List.of(ProjectType.GO); }

    @Override
    public Optional<DetectedProject> detect(Path projectRoot) {
        Path mod = projectRoot.resolve("go.mod");
        if (!Files.exists(mod)) return Optional.empty();
        DetectedProject p = new DetectedProject(ProjectType.GO, "go", List.of("build", "./..."), "go",
            List.of("test", "./..."), "go", List.of("vet", "./..."), null, List.of(), "go",
            List.of("go version"), List.of(), java.util.Map.of(), 0.9f);
        return Optional.of(p);
    }
}