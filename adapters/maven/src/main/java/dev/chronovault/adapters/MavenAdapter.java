package dev.chronovault.adapters.maven;

import dev.chronovault.sdk.DetectedProject;
import dev.chronovault.sdk.ProjectAdapter;
import dev.chronovault.sdk.ProjectType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public final class MavenAdapter implements ProjectAdapter {
    @Override public String id() { return "maven"; }
    @Override public String displayName() { return "Java Maven"; }
    @Override public List<ProjectType> supportedTypes() { return List.of(ProjectType.JAVA_MAVEN); }

    @Override
    public Optional<DetectedProject> detect(Path projectRoot) {
        Path pom = projectRoot.resolve("pom.xml");
        if (!Files.exists(pom)) return Optional.empty();
        return Optional.of(DetectedProject.of(ProjectType.JAVA_MAVEN, null, 0.95f)
            .withTestCommand("mvn test"));
    }
}