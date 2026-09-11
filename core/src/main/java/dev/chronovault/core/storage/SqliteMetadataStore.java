package dev.chronovault.core.storage;

import dev.chronovault.core.domain.*;
import dev.chronovault.core.util.JsonUtil;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class SqliteMetadataStore implements MetadataStore {
    private final String jdbcUrl;
    private Connection conn;

    public SqliteMetadataStore(java.nio.file.Path dbFile) {
        this.jdbcUrl = "jdbc:sqlite:" + dbFile.toAbsolutePath();
    }

    @Override
    public void initialize() {
        try {
            Class.forName("org.sqlite.JDBC");
            conn = DriverManager.getConnection(jdbcUrl);
            try (var st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
            }
            try (var st = conn.createStatement()) {
                st.execute("PRAGMA foreign_keys=ON");
            }
            try (var st = conn.createStatement()) {
                st.execute("PRAGMA busy_timeout=5000");
            }
            createSchema();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize SQLite metadata store", e);
        }
    }

    private void createSchema() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS projects (
                    project_id TEXT PRIMARY KEY,
                    root_path TEXT UNIQUE NOT NULL,
                    name TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                )
                """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS checkpoints (
                    checkpoint_id TEXT PRIMARY KEY,
                    project_id TEXT NOT NULL,
                    snapshot_id TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    label TEXT,
                    status TEXT NOT NULL,
                    health_json TEXT,
                    evidence_json TEXT,
                    git_branch TEXT,
                    git_commit TEXT,
                    pinned INTEGER DEFAULT 0,
                    auto_created INTEGER DEFAULT 0
                )
                """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_cp_project ON checkpoints(project_id, created_at DESC)");
            st.execute("""
                CREATE TABLE IF NOT EXISTS snapshots (
                    snapshot_id TEXT PRIMARY KEY,
                    project_id TEXT NOT NULL,
                    parent_snapshot_id TEXT,
                    type TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    manifest_json TEXT NOT NULL,
                    entries_json TEXT NOT NULL
                )
                """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_sn_project ON snapshots(project_id, created_at DESC)");
            st.execute("""
                CREATE TABLE IF NOT EXISTS operations (
                    operation_id TEXT PRIMARY KEY,
                    project_id TEXT NOT NULL,
                    stage TEXT NOT NULL,
                    progress INTEGER NOT NULL DEFAULT 0,
                    message TEXT,
                    target_checkpoint TEXT,
                    protective_snapshot TEXT,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    result_json TEXT
                )
                """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_op_project ON operations(project_id, created_at DESC)");
        }
    }

    private void ensureConnection() {
        if (conn == null) initialize();
    }

    @Override
    public ProjectId registerProject(String rootPath, String name) {
        ensureConnection();
        Optional<ProjectId> existing = findProjectByRoot(rootPath);
        if (existing.isPresent()) return existing.get();
        ProjectId id = ProjectId.generate();
        try (PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO projects (project_id, root_path, name, created_at) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, id.value());
            ps.setString(2, rootPath);
            ps.setString(3, name);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to register project", e);
        }
        return id;
    }

    @Override
    public Optional<ProjectId> findProjectByRoot(String rootPath) {
        ensureConnection();
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT project_id FROM projects WHERE root_path = ?")) {
            ps.setString(1, rootPath);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(ProjectId.of(rs.getString("project_id")));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return Optional.empty();
    }

    @Override
    public Optional<String> getProjectName(ProjectId projectId) {
        ensureConnection();
        try (PreparedStatement ps = conn.prepareStatement("SELECT name FROM projects WHERE project_id = ?")) {
            ps.setString(1, projectId.value());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(rs.getString("name"));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return Optional.empty();
    }

    @Override
    public void saveCheckpoint(Checkpoint cp) {
        ensureConnection();
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO checkpoints (checkpoint_id, project_id, snapshot_id, created_at, label, status,
                    health_json, evidence_json, git_branch, git_commit, pinned, auto_created)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(checkpoint_id) DO UPDATE SET
                    label=excluded.label, status=excluded.status, health_json=excluded.health_json,
                    evidence_json=excluded.evidence_json, git_branch=excluded.git_branch,
                    git_commit=excluded.git_commit, pinned=excluded.pinned, auto_created=excluded.auto_created
                """)) {
            ps.setString(1, cp.id().value());
            ps.setString(2, cp.projectId().value());
            ps.setString(3, cp.snapshotId().value());
            ps.setLong(4, cp.createdAt().toEpochMilli());
            ps.setString(5, cp.label());
            ps.setString(6, cp.status().name());
            ps.setString(7, cp.evidence() != null && cp.evidence().healthResult() != null
                ? JsonUtil.toJson(cp.evidence().healthResult()) : null);
            ps.setString(8, cp.evidence() != null ? JsonUtil.toJson(cp.evidence()) : null);
            ps.setString(9, cp.gitBranch());
            ps.setString(10, cp.gitCommit());
            ps.setInt(11, cp.pinned() ? 1 : 0);
            ps.setInt(12, cp.autoCreated() ? 1 : 0);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save checkpoint " + cp.id(), e);
        }
    }

    @Override
    public Optional<Checkpoint> getCheckpoint(ProjectId projectId, CheckpointId checkpointId) {
        ensureConnection();
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT * FROM checkpoints WHERE project_id = ? AND checkpoint_id = ?")) {
            ps.setString(1, projectId.value());
            ps.setString(2, checkpointId.value());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapCheckpoint(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return Optional.empty();
    }

    @Override
    public List<Checkpoint> listCheckpoints(ProjectId projectId) {
        return listCheckpoints(projectId, 500);
    }

    @Override
    public List<Checkpoint> listCheckpoints(ProjectId projectId, int limit) {
        ensureConnection();
        List<Checkpoint> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT * FROM checkpoints WHERE project_id = ? ORDER BY created_at DESC LIMIT ?")) {
            ps.setString(1, projectId.value());
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(mapCheckpoint(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    @Override
    public long countCheckpoints(ProjectId projectId) {
        ensureConnection();
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM checkpoints WHERE project_id = ?")) {
            ps.setString(1, projectId.value());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return 0;
    }

    @Override
    public Checkpoint markVerified(ProjectId projectId, CheckpointId checkpointId) {
        return setCheckpointStatus(projectId, checkpointId, CheckpointStatus.VERIFIED);
    }

    @Override
    public Checkpoint setCheckpointStatus(ProjectId projectId, CheckpointId checkpointId, CheckpointStatus status) {
        ensureConnection();
        try (PreparedStatement ps = conn.prepareStatement(
            "UPDATE checkpoints SET status = ? WHERE project_id = ? AND checkpoint_id = ?")) {
            ps.setString(1, status.name());
            ps.setString(2, projectId.value());
            ps.setString(3, checkpointId.value());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return getCheckpoint(projectId, checkpointId).orElseThrow(
            () -> new IllegalStateException("Checkpoint not found after status update: " + checkpointId));
    }

    @Override
    public void setPinned(ProjectId projectId, CheckpointId checkpointId, boolean pinned) {
        ensureConnection();
        try (PreparedStatement ps = conn.prepareStatement(
            "UPDATE checkpoints SET pinned = ? WHERE project_id = ? AND checkpoint_id = ?")) {
            ps.setInt(1, pinned ? 1 : 0);
            ps.setString(2, projectId.value());
            ps.setString(3, checkpointId.value());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private Checkpoint mapCheckpoint(ResultSet rs) throws SQLException {
        VerificationEvidence evidence = null;
        if (rs.getString("evidence_json") != null) {
            try {
                evidence = JsonUtil.fromJson(rs.getString("evidence_json"), VerificationEvidence.class);
            } catch (Exception ignored) {}
        }
        return new Checkpoint(
            CheckpointId.of(rs.getString("checkpoint_id")),
            ProjectId.of(rs.getString("project_id")),
            SnapshotId.of(rs.getString("snapshot_id")),
            Instant.ofEpochMilli(rs.getLong("created_at")),
            rs.getString("label"),
            CheckpointStatus.valueOf(rs.getString("status")),
            evidence,
            rs.getString("git_branch"),
            rs.getString("git_commit"),
            rs.getInt("pinned") == 1,
            rs.getInt("auto_created") == 1
        );
    }

    @Override
    public void saveSnapshotManifest(SnapshotManifest manifest) {
        ensureConnection();
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO snapshots (snapshot_id, project_id, parent_snapshot_id, type, created_at, manifest_json, entries_json)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(snapshot_id) DO UPDATE SET
                    type=excluded.type, manifest_json=excluded.manifest_json, entries_json=excluded.entries_json
                """)) {
            ps.setString(1, manifest.snapshotId().value());
            ps.setString(2, manifest.projectId().value());
            ps.setString(3, manifest.parentSnapshotId() != null ? manifest.parentSnapshotId().value() : null);
            ps.setString(4, manifest.type().name());
            ps.setLong(5, manifest.createdAt().toEpochMilli());
            ps.setString(6, JsonUtil.toJson(manifest));  // full record metadata
            ps.setString(7, JsonUtil.toJson(manifest.entries()));  // entries
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save snapshot manifest " + manifest.snapshotId(), e);
        }
    }

    @Override
    public Optional<SnapshotManifest> getSnapshotManifest(ProjectId projectId, SnapshotId snapshotId) {
        ensureConnection();
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT manifest_json, entries_json FROM snapshots WHERE project_id = ? AND snapshot_id = ?")) {
            ps.setString(1, projectId.value());
            ps.setString(2, snapshotId.value());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapManifest(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return Optional.empty();
    }

    @Override
    public Optional<SnapshotManifest> getLatestSnapshot(ProjectId projectId) {
        ensureConnection();
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT manifest_json, entries_json FROM snapshots WHERE project_id = ? ORDER BY created_at DESC LIMIT 1")) {
            ps.setString(1, projectId.value());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapManifest(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return Optional.empty();
    }

    @Override
    public List<SnapshotManifest> listSnapshots(ProjectId projectId) {
        ensureConnection();
        List<SnapshotManifest> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT manifest_json, entries_json FROM snapshots WHERE project_id = ? ORDER BY created_at DESC")) {
            ps.setString(1, projectId.value());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(mapManifest(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    @Override
    public long countSnapshots(ProjectId projectId) {
        ensureConnection();
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM snapshots WHERE project_id = ?")) {
            ps.setString(1, projectId.value());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return 0;
    }

    @Override
    public List<SnapshotId> allSnapshotIds() {
        ensureConnection();
        List<SnapshotId> out = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT snapshot_id FROM snapshots")) {
            while (rs.next()) out.add(SnapshotId.of(rs.getString(1)));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    private SnapshotManifest mapManifest(ResultSet rs) throws SQLException {
        SnapshotManifest manifest = JsonUtil.fromJson(rs.getString("manifest_json"), SnapshotManifest.class);
        List<ManifestEntry> entries = JsonUtil.fromJson(
            rs.getString("entries_json"),
            new com.google.gson.reflect.TypeToken<List<ManifestEntry>>() {}.getType());
        return new SnapshotManifest(
            manifest.snapshotId(), manifest.projectId(), manifest.type(),
            manifest.parentSnapshotId(), manifest.createdAt(),
            entries, manifest.totalLogicalBytes(), manifest.totalPhysicalBytes(), manifest.fileCount());
    }

    @Override
    public List<String> allContentHashes() {
        ensureConnection();
        List<String> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT entries_json FROM snapshots")) {
            while (rs.next()) {
                List<ManifestEntry> entries = JsonUtil.fromJson(
                    rs.getString(1), new com.google.gson.reflect.TypeToken<List<ManifestEntry>>() {}.getType());
                for (ManifestEntry e : entries) {
                    if (e.contentHash() != null) out.add(e.contentHash());
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    @Override
    public void deleteSnapshot(ProjectId projectId, SnapshotId snapshotId) {
        ensureConnection();
        try (PreparedStatement ps = conn.prepareStatement(
            "DELETE FROM snapshots WHERE project_id = ? AND snapshot_id = ?")) {
            ps.setString(1, projectId.value());
            ps.setString(2, snapshotId.value());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void deleteCheckpoint(ProjectId projectId, CheckpointId checkpointId) {
        ensureConnection();
        try (PreparedStatement ps = conn.prepareStatement(
            "DELETE FROM checkpoints WHERE project_id = ? AND checkpoint_id = ?")) {
            ps.setString(1, projectId.value());
            ps.setString(2, checkpointId.value());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void saveOperationState(RecoveryOperation operation) {
        ensureConnection();
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO operations (operation_id, project_id, stage, progress, message, target_checkpoint,
                    protective_snapshot, created_at, updated_at, result_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(operation_id) DO UPDATE SET
                    stage=excluded.stage, progress=excluded.progress, message=excluded.message,
                    target_checkpoint=excluded.target_checkpoint,
                    protective_snapshot=excluded.protective_snapshot,
                    updated_at=excluded.updated_at, result_json=excluded.result_json
                """)) {
            ps.setString(1, operation.operationId());
            ps.setString(2, operation.projectId().value());
            ps.setString(3, operation.stage().name());
            ps.setInt(4, operation.progress());
            ps.setString(5, operation.message());
            ps.setString(6, operation.targetCheckpoint() != null ? operation.targetCheckpoint().value() : null);
            ps.setString(7, operation.protectiveSnapshot() != null ? operation.protectiveSnapshot().value() : null);
            ps.setLong(8, operation.createdAt().toEpochMilli());
            ps.setLong(9, operation.updatedAt().toEpochMilli());
            ps.setString(10, operation.resultJson());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Optional<RecoveryOperation> getOperation(ProjectId projectId, String operationId) {
        ensureConnection();
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT * FROM operations WHERE project_id = ? AND operation_id = ?")) {
            ps.setString(1, projectId.value());
            ps.setString(2, operationId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapOperation(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return Optional.empty();
    }

    @Override
    public List<RecoveryOperation> listOperations(ProjectId projectId) {
        ensureConnection();
        List<RecoveryOperation> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT * FROM operations WHERE project_id = ? ORDER BY created_at DESC LIMIT 200")) {
            ps.setString(1, projectId.value());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(mapOperation(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    @Override
    public long countOperations(ProjectId projectId) {
        ensureConnection();
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM operations WHERE project_id = ?")) {
            ps.setString(1, projectId.value());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return 0;
    }

    private RecoveryOperation mapOperation(ResultSet rs) throws SQLException {
        return new RecoveryOperation(
            rs.getString("operation_id"),
            ProjectId.of(rs.getString("project_id")),
            OperationStage.valueOf(rs.getString("stage")),
            rs.getInt("progress"),
            rs.getString("message"),
            rs.getString("target_checkpoint") != null ? CheckpointId.of(rs.getString("target_checkpoint")) : null,
            rs.getString("protective_snapshot") != null ? SnapshotId.of(rs.getString("protective_snapshot")) : null,
            Instant.ofEpochMilli(rs.getLong("created_at")),
            Instant.ofEpochMilli(rs.getLong("updated_at")),
            rs.getString("result_json")
        );
    }

    @Override
    public long physicalStorageBytes() {
        ensureConnection();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COALESCE(SUM(page_count * page_size), 0) FROM pragma_page_count, pragma_page_size")) {
            if (rs.next()) return rs.getLong(1);
        } catch (SQLException e) {
            // pragma query with two pragmas may not work in all versions
        }
        return 0;
    }

    @Override
    public long logicalStorageBytes() {
        ensureConnection();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COALESCE(SUM(totalLogicalBytes * 1.0), 0) FROM snapshots")) {
            // placeholder - logical bytes computed from manifests elsewhere
        } catch (SQLException e) {
            // ignore
        }
        return 0;
    }

    @Override
    public void close() {
        try {
            if (conn != null) conn.close();
        } catch (SQLException ignored) {}
    }
}