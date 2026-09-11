package dev.chronovault.adapters.node;

import dev.chronovault.sdk.DetectedProject;
import dev.chronovault.sdk.ProjectAdapter;
import dev.chronovault.sdk.ProjectType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class NodeAdapter implements ProjectAdapter {
    @Override public String id() { return "node"; }
    @Override public String displayName() { return "Node.js"; }
    @Override public List<ProjectType> supportedTypes() { return List.of(ProjectType.NODE); }

    private static final Pattern SCRIPT = Pattern.compile("\"\\s*(build|test)\\s*\"\\s*:\\s*\"([^\"]+)\"");

    @Override
    public Optional<DetectedProject> detect(Path projectRoot) {
        Path pkg = projectRoot.resolve("package.json");
        if (!Files.exists(pkg)) return Optional.empty();
        try {
            String raw = Files.readString(pkg);
            boolean hasLock = Files.exists(projectRoot.resolve("package-lock.json"))
                || Files.exists(projectRoot.resolve("pnpm-lock.yaml"))
                || Files.exists(projectRoot.resolve("yarn.lock"));
            boolean hasBuild = false;
            boolean hasTest = false;
            Matcher m = SCRIPT.matcher(raw);
            while (m.find()) {
                if (m.group(1).equals("build")) hasBuild = true;
                else hasTest = true;
            }
            DetectedProject base = DetectedProject.of(ProjectType.NODE,
                hasBuild ? "npm run build" : null, hasLock ? 0.95f : 0.9f)
                .withMetadata("packageManager", "npm");
            if (hasTest) base = base.withTestCommand("npm test");
            return Optional.of(base);
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}