package dev.chronovault.core.domain;

public enum HealthStatus {
    PASS("Pass", "Check completed successfully"),
    FAIL("Fail", "Check failed"),
    SKIPPED("Skipped", "Check was not run"),
    ERROR("Error", "Check could not be executed");

    private final String label;
    private final String description;

    HealthStatus(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String getLabel() { return label; }
    public String getDescription() { return description; }
}