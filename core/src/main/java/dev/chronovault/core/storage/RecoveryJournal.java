package dev.chronovault.core.storage;

import dev.chronovault.core.domain.OperationStage;
import dev.chronovault.core.domain.RecoveryOperation;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Durable recovery journal. Appends each stage transition as a JSON line with fsync,
 * so that interrupted operations can be reconstructed after a crash.
 */
public final class RecoveryJournal {
    private final Path journalFile;
    private final List<JournalEntry> entries = new ArrayList<>();

    public record JournalEntry(
        String operationId,
        String projectId,
        OperationStage stage,
        int progress,
        String message,
        String targetCheckpoint,
        String protectiveSnapshot,
        long timestamp
    ) {}

    public RecoveryJournal(Path vaultDir) throws IOException {
        this.journalFile = vaultDir.resolve("journal.ndjson");
        Files.createDirectories(vaultDir);
        load();
    }

    private synchronized void load() throws IOException {
        entries.clear();
        if (!Files.exists(journalFile)) return;
        for (String line : Files.readAllLines(journalFile, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            try {
                String[] parts = line.split("\t", -1);
                if (parts.length < 9) continue;
                entries.add(new JournalEntry(
                    parts[0], parts[1], OperationStage.valueOf(parts[2]),
                    Integer.parseInt(parts[3]), parts[4].equals("<null>") ? null : parts[4],
                    parts[5].equals("<null>") ? null : parts[5],
                    parts[6].equals("<null>") ? null : parts[6],
                    Long.parseLong(parts[7])
                ));
            } catch (Exception ignored) {}
        }
    }

    public synchronized void append(RecoveryOperation op) throws IOException {
        JournalEntry e = new JournalEntry(
            op.operationId(), op.projectId().value(), op.stage(), op.progress(),
            op.message(), op.targetCheckpoint() != null ? op.targetCheckpoint().value() : null,
            op.protectiveSnapshot() != null ? op.protectiveSnapshot().value() : null,
            System.currentTimeMillis()
        );
        entries.add(e);
        String line = String.join("\t",
            e.operationId(), e.projectId(), e.stage().name(), String.valueOf(e.progress()),
            e.message() == null ? "<null>" : e.message(),
            e.targetCheckpoint() == null ? "<null>" : e.targetCheckpoint(),
            e.protectiveSnapshot() == null ? "<null>" : e.protectiveSnapshot(),
            String.valueOf(e.timestamp()), "cv1") + "\n";
        Files.write(journalFile, line.getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        try (var ch = FileChannel.open(journalFile, StandardOpenOption.WRITE)) {
            ch.force(true);
        }
    }

    public synchronized List<JournalEntry> snapshot() {
        return List.copyOf(entries);
    }

    public synchronized List<RecoveryOperation> incompleteOperations() {
        // The journal is an append-only log; a finished operation still has
        // intermediate (PROTECTING/PROTECTED/RESTORING) entries. Only the latest
        // entry per operation determines whether it is still incomplete.
        java.util.LinkedHashMap<String, JournalEntry> latest = new java.util.LinkedHashMap<>();
        for (JournalEntry e : entries) latest.put(e.operationId(), e);
        List<RecoveryOperation> out = new ArrayList<>();
        for (JournalEntry e : latest.values()) {
            if (e.stage() == OperationStage.PLANNED ||
                e.stage() == OperationStage.PROTECTING ||
                e.stage() == OperationStage.PROTECTED ||
                e.stage() == OperationStage.RESTORING ||
                e.stage() == OperationStage.VERIFYING) {
                RecoveryOperation op = new RecoveryOperation(
                    e.operationId(), dev.chronovault.core.domain.ProjectId.of(e.projectId()),
                    e.stage(), e.progress(), e.message(),
                    e.targetCheckpoint() != null ? dev.chronovault.core.domain.CheckpointId.of(e.targetCheckpoint()) : null,
                    e.protectiveSnapshot() != null ? dev.chronovault.core.domain.SnapshotId.of(e.protectiveSnapshot()) : null,
                    Instant.ofEpochMilli(e.timestamp()), Instant.now(), null
                );
                out.add(op);
            }
        }
        return out;
    }

    public synchronized void markCanceled(String operationId) throws IOException {
        // write a cancelling marker for recovery
        for (JournalEntry e : entries) {
            if (e.operationId().equals(operationId) &&
                (e.stage() == OperationStage.PLANNED || e.stage() == OperationStage.PROTECTING ||
                 e.stage() == OperationStage.PROTECTED || e.stage() == OperationStage.RESTORING)) {
                RecoveryOperation cancel = new RecoveryOperation(
                    operationId, dev.chronovault.core.domain.ProjectId.of(e.projectId()),
                    OperationStage.CANCELLED, 0, "Canceled",
                    e.targetCheckpoint() != null ? dev.chronovault.core.domain.CheckpointId.of(e.targetCheckpoint()) : null,
                    e.protectiveSnapshot() != null ? dev.chronovault.core.domain.SnapshotId.of(e.protectiveSnapshot()) : null,
                    Instant.ofEpochMilli(e.timestamp()), Instant.now(), null
                );
                append(cancel);
                return;
            }
        }
    }
}