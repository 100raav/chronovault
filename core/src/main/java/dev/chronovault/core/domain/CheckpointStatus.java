package dev.chronovault.core.domain;

public enum CheckpointStatus {
    UNVERIFIED("Unverified", "Not yet verified — recovery target blocked"),
    VERIFIED("Verified", "Health verification passed — safe recovery target"),
    BROKEN("Broken", "Health verification failed"),
    RECOVERING("Recovering", "Currently being recovered to"),
    ACTIVE("Active", "Currently active state"),
    ROLLED_BACK("Rolled Back", "Recovery to this state was rolled back");

    private final String label;
    private final String description;

    CheckpointStatus(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String getLabel() { return label; }
    public String getDescription() { return description; }
}