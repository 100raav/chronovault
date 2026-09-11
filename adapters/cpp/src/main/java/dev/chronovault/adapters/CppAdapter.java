package dev.chronovault.adapters.cpp;

import dev.chronovault.sdk.DetectedProject;
import dev.chronovault.sdk.ProjectAdapter;
import dev.chronovault.sdk.ProjectType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public final class CppAdapter implements ProjectAdapter {
    @Override public String id() { return "cpp"; }
    @Override public String displayName() { return "C/C++"; }
    @Override public List<ProjectType> supportedTypes() { return List.of(ProjectType.CPP); }

    @Override
    public Optional<DetectedProject> detect(Path projectRoot) {
        boolean hasCMake = Files.exists(projectRoot.resolve("CMakeLists.txt"));
        boolean hasMake = Files.exists(projectRoot.resolve("Makefile"));
        boolean hasSrc = Files.exists(projectRoot.resolve("src")) &&
            (Files.exists(projectRoot.resolve("src/main.cpp"))
                || Files.exists(projectRoot.resolve("src/main.c")));
        if (!hasCMake && !hasMake && !hasSrc) return Optional.empty();
        String cmd = hasCMake ? "cmake" : "make";
        DetectedProject p = new DetectedProject(ProjectType.CPP, cmd, List.of(), null, List.of(),
            null, List.of(), null, List.of(), hasCMake ? "cmake" : null,
            List.of("gcc --version", "clang --version"), List.of(), java.util.Map.of(), 0.8f);
        return Optional.of(p);
    }
}