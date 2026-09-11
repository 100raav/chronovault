package dev.chronovault.core.diff;

import dev.chronovault.core.domain.ManifestEntry;
import dev.chronovault.core.domain.RestoreAction;
import dev.chronovault.core.util.PathSafety;

import java.util.*;

public final class DiffEngine {

    public record Change(
        String path,
        ChangeKind kind,
        long size,
        String oldHash,
        String newHash
    ) {
        public enum ChangeKind { ADDED, MODIFIED, DELETED, RENAMED, SYMLINK }
    }

    public record DiffResult(
        List<RestoreAction> actions,
        List<Change> changes,
        int added,
        int modified,
        int deleted,
        int renamed
    ) {
        public int total() { return added + modified + deleted + renamed; }
    }

    public DiffResult diff(List<ManifestEntry> from, List<ManifestEntry> to) {
        Map<String, ManifestEntry> fromMap = index(from);
        Map<String, ManifestEntry> toMap = index(to);

        List<RestoreAction> actions = new ArrayList<>();
        List<Change> changes = new ArrayList<>();
        int added = 0, modified = 0, deleted = 0, renamed = 0;

        // Map target content hash -> paths (for rename detection).
        Map<String, List<String>> targetHashToPaths = new HashMap<>();
        for (ManifestEntry e : to) {
            if (e.contentHash() != null) {
                targetHashToPaths.computeIfAbsent(e.contentHash(), k -> new ArrayList<>()).add(e.path());
            }
        }

        Set<String> targetPaths = toMap.keySet();

        // Identify paths present in target (files/symlinks to be materialized).
        // Directory entries are derived from the file tree; they never produce actions.
        for (ManifestEntry target : to) {
            if (target.kind() == ManifestEntry.EntryKind.DIRECTORY) continue;
            ManifestEntry fromEntry = fromMap.get(target.path());
            boolean needsWrite = fromEntry == null
                || fromEntry.kind() != target.kind()
                || !Objects.equals(fromEntry.contentHash(), target.contentHash())
                || !Objects.equals(fromEntry.symlinkTarget(), target.symlinkTarget());
            Change.ChangeKind kind = fromEntry == null ? Change.ChangeKind.ADDED : Change.ChangeKind.MODIFIED;
            if (target.kind() == ManifestEntry.EntryKind.SYMLINK) {
                if (!needsWrite) continue;
                actions.add(new RestoreAction.PutSymlink(target.path(), target.symlinkTarget()));
                changes.add(new Change(target.path(), kind, 0, null, null));
            } else if (fromEntry == null) {
                actions.add(new RestoreAction.PutFile(target.path(), target.contentHash(), target.size(), target.permissions()));
                changes.add(new Change(target.path(), Change.ChangeKind.ADDED, target.size(), null, target.contentHash()));
            } else if (!Objects.equals(fromEntry.contentHash(), target.contentHash())) {
                actions.add(new RestoreAction.PutFile(target.path(), target.contentHash(), target.size(), target.permissions()));
                changes.add(new Change(target.path(), Change.ChangeKind.MODIFIED, target.size(), fromEntry.contentHash(), target.contentHash()));
            } else {
                continue;  // same content on both sides (e.g. permissions-only); no action
            }
            if (fromEntry == null) added++; else modified++;
        }

        // Files present in source but absent in target → deleted or renamed.
        for (ManifestEntry fromEntry : from) {
            if (fromEntry.kind() == ManifestEntry.EntryKind.DIRECTORY) continue;
            if (targetPaths.contains(fromEntry.path())) continue;
            String hash = fromEntry.contentHash();
            if (hash != null) {
                List<String> sameContentPaths = targetHashToPaths.get(hash);
                if (sameContentPaths != null) {
                    Optional<String> renamedTo = sameContentPaths.stream()
                        .filter(p -> !fromMap.containsKey(p))   // new location wasn't in source
                        .findFirst();
                    if (renamedTo.isPresent()) {
                        String newPath = renamedTo.get();
                        // Remove the earlier ADDED classification for the new path.
                        changes.removeIf(c -> c.path().equals(newPath) && c.kind() == Change.ChangeKind.ADDED);
                        changes.add(new Change(newPath, Change.ChangeKind.RENAMED, fromEntry.size(), hash, hash));
                        added--;
                        renamed++;
                        continue; // do not delete the old path
                    }
                }
            }
            actions.add(new RestoreAction.Remove(fromEntry.path()));
            changes.add(new Change(fromEntry.path(), Change.ChangeKind.DELETED, fromEntry.size(), hash, null));
            deleted++;
        }

        return new DiffResult(
            actions.stream()
                .filter(a -> PathSafety.isSafeRelativePath(a.path()))
                .toList(),
            changes, added, modified, deleted, renamed);
    }

    private Map<String, ManifestEntry> index(List<ManifestEntry> entries) {
        Map<String, ManifestEntry> map = new HashMap<>();
        for (ManifestEntry e : entries) {
            map.put(e.path(), e);
        }
        return map;
    }
}