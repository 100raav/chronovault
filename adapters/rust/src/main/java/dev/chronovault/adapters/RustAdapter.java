package dev.chronovault.adapters.rust;

import dev.chronovault.sdk.DetectedProject;
import dev.chronovault.sdk.ProjectAdapter;
import dev.chronovault.sdk.ProjectType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public final class RustAdapter implements ProjectAdapter {
    @Override public String id() { return "rust"; }
    @Override public String displayName() { return "Rust"; }
    @Override public List<ProjectType> supportedTypes() { return List.of(ProjectType.RUST); }

    @Override
    public Optional<DetectedProject> detect(Path projectRoot) {
        Path cargo = projectRoot.resolve("Cargo.toml");
        if (!Files.exists(cargo)) return Optional.empty();
        DetectedProject p = new DetectedProject(ProjectType.RUST, "cargo", List.of("build"), "cargo",
            List.of("test"), null, List.of(), null, List.of(), "cargo",
            List.of("rustc --version"), List.of(), java.util.Map.of("hasCargoLock", "cargo"),
            0.9f);
        return Optional.of(p);
    }
}