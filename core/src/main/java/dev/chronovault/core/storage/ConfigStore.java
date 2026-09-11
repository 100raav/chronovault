package dev.chronovault.core.storage;

import com.google.gson.Gson;
import dev.chronovault.core.domain.VaultConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ConfigStore {
    public static Path configPath(Path vaultRoot) {
        return vaultRoot.resolve("config.json");
    }

    public static void save(Path vaultRoot, VaultConfig config) throws IOException {
        Path path = configPath(vaultRoot);
        Files.createDirectories(vaultRoot);
        Files.writeString(path, new Gson().toJson(config), StandardCharsets.UTF_8);
    }

    public static VaultConfig load(Path vaultRoot) throws IOException {
        Path path = configPath(vaultRoot);
        if (!Files.exists(path)) {
            throw new IOException("No chronovault config found at " + path);
        }
        return new Gson().fromJson(Files.readString(path, StandardCharsets.UTF_8), VaultConfig.class);
    }

    public static boolean exists(Path vaultRoot) {
        return Files.exists(configPath(vaultRoot));
    }
}