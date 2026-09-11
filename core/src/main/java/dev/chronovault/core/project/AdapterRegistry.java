package dev.chronovault.core.project;

import dev.chronovault.sdk.DetectedProject;
import dev.chronovault.sdk.ProjectAdapter;
import dev.chronovault.sdk.ProjectType;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Discovers project adapters via {@link ServiceLoader} and picks the best match
 * for a given project root.
 */
public final class AdapterRegistry {
    private final List<ProjectAdapter> adapters = new ArrayList<>();

    public AdapterRegistry() {
        ServiceLoader.load(ProjectAdapter.class).forEach(adapters::add);
    }

    public AdapterRegistry(List<ProjectAdapter> customAdapters) {
        adapters.addAll(customAdapters);
        ServiceLoader.load(ProjectAdapter.class).forEach(adapters::add);
    }

    public List<ProjectAdapter> all() {
        return List.copyOf(adapters);
    }

    public Optional<DetectedProject> detect(Path projectRoot) {
        return adapters.stream()
            .map(a -> a.detect(projectRoot))
            .filter(Optional::isPresent)
            .map(Optional::get)
            .max(Comparator.comparingDouble(DetectedProject::confidence));
    }

    public List<DetectedProject> detectAll(Path projectRoot) {
        return adapters.stream()
            .map(a -> a.detect(projectRoot))
            .filter(Optional::isPresent)
            .map(Optional::get)
            .sorted(Comparator.comparingDouble(DetectedProject::confidence).reversed())
            .toList();
    }
}