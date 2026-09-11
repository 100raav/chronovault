package dev.chronovault.adapters.gradle;

import dev.chronovault.sdk.DetectedProject;
import dev.chronovault.sdk.ProjectAdapter;
import dev.chronovault.sdk.ProjectType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public final class GradleAdapter implements ProjectAdapter {
    @Override public String id() { return "gradle"; }
    @Override public String displayName() { return "Java/Kotlin Gradle"; }
    @Override public List<ProjectType> supportedTypes() { return List.of(ProjectType.JAVA_GRADLE, ProjectType.KOTLIN_GRADLE); }

    @Override
    public Optional<DetectedProject> detect(Path projectRoot) {
        Path gradleFile = projectRoot.resolve("build.gradle");
        Path kts = projectRoot.resolve("build.gradle.kts");
        boolean javaGradle = Files.exists(gradleFile);
        boolean kotlinGradle = Files.exists(kts);
        if (!javaGradle && !kotlinGradle) return Optional.empty();
        ProjectType type = kotlinGradle ? ProjectType.KOTLIN_GRADLE : ProjectType.JAVA_GRADLE;
        return Optional.of(DetectedProject.of(type, "gradle", 0.9f)
            .withTestCommand("gradle test"));
    }
}