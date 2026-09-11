package dev.chronovault.core.domain;

import java.util.List;

public record RestorePlan(
    CheckpointId targetCheckpointId,
    SnapshotId sourceSnapshotId,
    SnapshotId targetSnapshotId,
    ProjectId projectId,
    List<RestoreAction> actions,
    int modifiedFiles,
    int addedFiles,
    int deletedFiles,
    int renamedFiles,
    long totalBytes,
    SnapshotId protectiveSnapshotId,
    List<String> dependencyChanges
) {
    public static RestorePlan filtered(RestorePlan plan,
                                       java.util.function.Predicate<String> keep) {
        List<RestoreAction> filtered = plan.actions().stream()
            .filter(a -> keep.test(a.path())).toList();
        int modified = 0;
        int deleted = 0;
        int bytes = 0;
        for (RestoreAction a : filtered) {
            switch (a) {
                case RestoreAction.PutFile pf -> { modified++; bytes += pf.size(); }
                case RestoreAction.PutSymlink ignored -> modified++;
                case RestoreAction.Remove ignored -> deleted++;
            }
        }
        return new RestorePlan(plan.targetCheckpointId(), plan.sourceSnapshotId(),
            plan.targetSnapshotId(), plan.projectId(), filtered, modified, 0, deleted, 0,
            bytes, plan.protectiveSnapshotId(), plan.dependencyChanges());
    }
}