package dev.chronovault.core.project;

import dev.chronovault.core.domain.*;
import dev.chronovault.core.util.Hashing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FingerprintService {
    public EnvironmentFingerprint environment() {
        Map<String, String> tools = new LinkedHashMap<>();
        tools.put("java", System.getProperty("java.version", ""));
        for (String tool : List.of("mvn", "gradle", "node", "npm", "python3", "python", "cargo", "go", "dotnet", "gcc", "clang")) {
            String ver = probeVersion(tool);
            if (!ver.isBlank()) tools.put(tool, ver);
        }
        return new EnvironmentFingerprint(
            System.getProperty("os.name", "unknown"),
            System.getProperty("os.arch", "unknown"),
            System.getProperty("java.version", ""),
            tools
        );
    }

    public ToolchainFingerprint toolchain() {
        return new ToolchainFingerprint(Hashing.hashString(System.getProperty("os.name") + "|" +
            System.getProperty("os.arch") + "|" + System.getProperty("java.version")),
            environment().tools());
    }

    public DependencyFingerprint dependencies(Path root, List<String> manifestNames) {
        Map<String, String> manifests = new LinkedHashMap<>();
        for (String name : manifestNames) {
            Path p = root.resolve(name);
            if (Files.isRegularFile(p)) {
                try {
                    String content = Files.readString(p, StandardCharsets.UTF_8);
                    manifests.put(name, Hashing.hashString(content));
                } catch (IOException ignored) {}
            }
        }
        String fp = Hashing.hashString(String.join("|", manifests.values()) + "|" + String.join(",", manifests.keySet()));
        return new DependencyFingerprint(fp, manifests);
    }

    private String probeVersion(String tool) {
        try {
            ProcessBuilder pb = new ProcessBuilder(tool, "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            byte[] buf = p.getInputStream().readNBytes(2048);
            p.waitFor();
            return new String(buf, StandardCharsets.UTF_8).trim().split("\n")[0];
        } catch (Exception e) {
            return "";
        }
    }

    public static class GitState {
        public final String branch;
        public final String commit;

        public GitState(String branch, String commit) {
            this.branch = branch;
            this.commit = commit;
        }
    }

    public GitState gitState(Path root) {
        String branch = git(root, "rev-parse", "--abbrev-ref", "HEAD");
        String commit = git(root, "rev-parse", "HEAD");
        return new GitState(branch, commit);
    }

    private String git(Path root, String... args) {
        try {
            List<String> cmd = new ArrayList<>(List.of("git"));
            cmd.addAll(List.of(args));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(root.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            byte[] buf = p.getInputStream().readNBytes(4096);
            p.waitFor();
            return new String(buf, StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return null;
        }
    }

    public List<String> detectManifestNames(Path root) {
        List<String> names = new ArrayList<>();
        for (String n : List.of("pom.xml", "build.gradle", "build.gradle.kts", "package.json",
            "package-lock.json", "pnpm-lock.yaml", "yarn.lock", "requirements.txt", "pyproject.toml",
            "Pipfile.lock", "poetry.lock", "Cargo.toml", "Cargo.lock", "go.mod", "go.sum",
            "*.csproj", "CMakeLists.txt", "Makefile")) {
            if (n.contains("*")) {
                try (var stream = Files.list(root)) {
                    boolean any = stream.anyMatch(p -> p.getFileName().toString().matches(n.replace("*", ".*")));
                    if (any) names.add(n);
                } catch (IOException ignored) {}
            } else if (Files.exists(root.resolve(n))) {
                names.add(n);
            }
        }
        return names;
    }
}