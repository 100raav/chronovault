package dev.chronovault.adapters.dotnet;

import dev.chronovault.sdk.DetectedProject;
import dev.chronovault.sdk.ProjectAdapter;
import dev.chronovault.sdk.ProjectType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public final class DotnetAdapter implements ProjectAdapter {
    @Override public String id() { return "dotnet"; }
    @Override public String displayName() { return ".NET"; }
    @Override public List<ProjectType> supportedTypes() { return List.of(ProjectType.DOTNET); }

    @Override
    public Optional<DetectedProject> detect(Path projectRoot) {
        boolean hasCsproj = false;
        try (Stream<Path> stream = Files.walk(projectRoot, 2)) {
            hasCsproj = stream.anyMatch(p -> p.getFileName().toString().endsWith(".csproj"));
        } catch (IOException ignored) {}
        if (!hasCsproj) return Optional.empty();
        DetectedProject p = new DetectedProject(ProjectType.DOTNET, "dotnet", List.of("build"), "dotnet",
            List.of("test"), null, List.of(), null, List.of(), "nuget",
            List.of("dotnet --version"), List.of(), java.util.Map.of(), 0.9f);
        return Optional.of(p);
    }
}