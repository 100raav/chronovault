package dev.chronovault.core.project;

import dev.chronovault.core.domain.ProjectId;
import dev.chronovault.core.domain.VaultConfig;

import java.nio.file.Path;

public record VaultLayout(
    Path projectRoot,
    Path vaultRoot,
    Path metadataDb,
    Path objectsRoot,
    Path configFile,
    Path journalFile
) {
    public static VaultLayout of(Path projectRoot) {
        Path vault = projectRoot.resolve(".chronovault");
        return new VaultLayout(
            projectRoot, vault,
            vault.resolve("metadata.db"),
            vault.resolve("objects"),
            vault.resolve("config.json"),
            vault.resolve("journal.ndjson")
        );
    }

    public boolean isInitialized() {
        return vaultRoot.resolve("metadata.db").toFile().exists() ||
            vaultRoot.resolve("config.json").toFile().exists();
    }
}