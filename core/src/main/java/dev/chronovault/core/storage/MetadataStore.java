package dev.chronovault.core.storage;

import dev.chronovault.core.domain.*;

import java.util.List;
import java.util.Optional;

public interface MetadataStore extends AutoCloseable {
    void initialize();

    ProjectId registerProject(String rootPath, String name) ;
    Optional<ProjectId> findProjectByRoot(String rootPath);
    Optional<String> getProjectName(ProjectId projectId);

    void saveCheckpoint(Checkpoint checkpoint);
    Optional<Checkpoint> getCheckpoint(ProjectId projectId, CheckpointId checkpointId);
    List<Checkpoint> listCheckpoints(ProjectId projectId);
    List<Checkpoint> listCheckpoints(ProjectId projectId, int limit);
    long countCheckpoints(ProjectId projectId);
    Checkpoint markVerified(ProjectId projectId, CheckpointId checkpointId);
    Checkpoint setCheckpointStatus(ProjectId projectId, CheckpointId checkpointId, CheckpointStatus status);
    void setPinned(ProjectId projectId, CheckpointId checkpointId, boolean pinned);

    void saveSnapshotManifest(SnapshotManifest manifest);
    Optional<SnapshotManifest> getSnapshotManifest(ProjectId projectId, SnapshotId snapshotId);
    Optional<SnapshotManifest> getLatestSnapshot(ProjectId projectId);
    List<SnapshotManifest> listSnapshots(ProjectId projectId);
    long countSnapshots(ProjectId projectId);
    List<SnapshotId> allSnapshotIds();

    List<String> allContentHashes();
    void deleteSnapshot(ProjectId projectId, SnapshotId snapshotId);
    void deleteCheckpoint(ProjectId projectId, CheckpointId checkpointId);

    void saveOperationState(RecoveryOperation operation);
    Optional<RecoveryOperation> getOperation(ProjectId projectId, String operationId);
    List<RecoveryOperation> listOperations(ProjectId projectId);
    long countOperations(ProjectId projectId);

    long physicalStorageBytes();
    long logicalStorageBytes();

    void close();
}