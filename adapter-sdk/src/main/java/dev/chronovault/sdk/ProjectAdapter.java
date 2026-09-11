package dev.chronovault.sdk;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public interface ProjectAdapter {
    String id();

    String displayName();

    List<ProjectType> supportedTypes();

    Optional<DetectedProject> detect(Path projectRoot);

    default boolean canDetect(Path projectRoot) {
        return detect(projectRoot).isPresent();
    }
}
