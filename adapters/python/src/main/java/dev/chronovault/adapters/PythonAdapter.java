package dev.chronovault.adapters.python;

import dev.chronovault.sdk.DetectedProject;
import dev.chronovault.sdk.ProjectAdapter;
import dev.chronovault.sdk.ProjectType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public final class PythonAdapter implements ProjectAdapter {
    @Override public String id() { return "python"; }
    @Override public String displayName() { return "Python"; }
    @Override public List<ProjectType> supportedTypes() { return List.of(ProjectType.PYTHON); }

    @Override
    public Optional<DetectedProject> detect(Path projectRoot) {
        boolean hasSetup = Files.exists(projectRoot.resolve("setup.py"))
            || Files.exists(projectRoot.resolve("pyproject.toml"))
            || Files.exists(projectRoot.resolve("Pipfile"))
            || Files.exists(projectRoot.resolve("requirements.txt"));
        boolean hasPythonSources = hasPythonSources(projectRoot.resolve("src"))
            || hasPythonSources(projectRoot);
        if (!hasSetup && !hasPythonSources) return Optional.empty();
        boolean hasPytest = Files.exists(projectRoot.resolve("pytest.ini"))
            || Files.exists(projectRoot.resolve("tox.ini"))
            || Files.exists(projectRoot.resolve("setup.cfg"));
        boolean hasTests = Files.exists(projectRoot.resolve("test"))
            || Files.exists(projectRoot.resolve("tests"));
        String testCmd = hasPytest ? "python3 -m pytest"
            : hasTests ? "python3 -m unittest discover -s test"
            : null;
        DetectedProject base = DetectedProject.of(ProjectType.PYTHON, null, 0.8f);
        return testCmd == null ? Optional.of(base) : Optional.of(base.withTestCommand(testCmd));
    }

    private static boolean hasPythonSources(Path root) {
        if (!Files.isDirectory(root)) return false;
        try (var stream = Files.walk(root, 3)) {
            return stream.anyMatch(p -> p.getFileName().toString().endsWith(".py"));
        } catch (Exception e) {
            return false;
        }
    }
}