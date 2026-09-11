package dev.chronovault.core.util;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;

public final class IgnoreMatcher {
    private final List<PathMatcher> nameMatchers;
    private final List<PathMatcher> pathMatchers;

    public IgnoreMatcher(List<String> patterns) {
        var names = new java.util.ArrayList<PathMatcher>();
        var paths = new java.util.ArrayList<PathMatcher>();
        for (String raw : patterns) {
            String p = raw.trim();
            if (p.isEmpty()) continue;
            if (p.startsWith("/")) {
                paths.add(FileSystems.getDefault().getPathMatcher("glob:" + p));
            } else if (p.contains("/")) {
                paths.add(FileSystems.getDefault().getPathMatcher("glob:" + p));
                paths.add(FileSystems.getDefault().getPathMatcher("glob:**/" + p));
            } else {
                names.add(FileSystems.getDefault().getPathMatcher("glob:" + p));
            }
        }
        this.nameMatchers = List.copyOf(names);
        this.pathMatchers = List.copyOf(paths);
    }

    public boolean isIgnored(Path file, Path root) {
        if (file == null) return false;
        Path name = file.getFileName();
        if (name != null) {
            for (PathMatcher m : nameMatchers) {
                if (m.matches(name)) return true;
            }
        }
        Path rel = root.relativize(file);
        String relStr = rel.toString().replace('\\', '/');
        for (PathMatcher m : pathMatchers) {
            if (m.matches(rel)) return true;
            if (m.matches(FileSystems.getDefault().getPath(relStr))) return true;
        }
        return false;
    }

    public boolean isIgnoredName(String fileName) {
        Path p = FileSystems.getDefault().getPath(fileName);
        for (PathMatcher m : nameMatchers) {
            if (m.matches(p)) return true;
        }
        return false;
    }
}