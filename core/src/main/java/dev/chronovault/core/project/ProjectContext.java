package dev.chronovault.core.project;

import dev.chronovault.core.domain.ProjectId;
import dev.chronovault.core.domain.VaultConfig;
import dev.chronovault.core.storage.ContentStore;
import dev.chronovault.core.storage.MetadataStore;

import java.nio.file.Path;

public record ProjectContext(
    ProjectId projectId,
    Path root,
    String name,
    VaultConfig config,
    ContentStore contentStore,
    MetadataStore metadataStore
) {
    public static ProjectContext of(
        ProjectId projectId, Path root, String name, VaultConfig config,
        ContentStore contentStore, MetadataStore metadataStore) {
        return new ProjectContext(projectId, root, name, config, contentStore, metadataStore);
    }
}