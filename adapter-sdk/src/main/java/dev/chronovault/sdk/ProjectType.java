package dev.chronovault.sdk;

public enum ProjectType {
    JAVA_MAVEN("Java (Maven)", "mvn"),
    JAVA_GRADLE("Java (Gradle)", "gradle"),
    KOTLIN_GRADLE("Kotlin (Gradle)", "gradle"),
    NODE("Node.js", "node"),
    PYTHON("Python", "python"),
    RUST("Rust", "cargo"),
    GO("Go", "go"),
    DOTNET(".NET", "dotnet"),
    CPP("C/C++", "cmake"),
    GENERIC("Generic", "make");

    private final String displayName;
    private final String defaultBuildCommand;

    ProjectType(String displayName, String defaultBuildCommand) {
        this.displayName = displayName;
        this.defaultBuildCommand = defaultBuildCommand;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDefaultBuildCommand() {
        return defaultBuildCommand;
    }
}
