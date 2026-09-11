package dev.chronovault.core.diff;

import dev.chronovault.core.domain.ManifestEntry;
import dev.chronovault.core.domain.RestoreAction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DiffEngineTest {

    private ManifestEntry e(String path, String hash, long size) {
        return new ManifestEntry(path, ManifestEntry.EntryKind.FILE, size, hash, null, null, 0);
    }

    @Test
    void detectsModifyAddDelete() {
        List<ManifestEntry> from = List.of(
            e("a.txt", "h1", 2), e("b.txt", "h2", 3), e("gone.txt", "h3", 4));
        List<ManifestEntry> to = List.of(
            e("a.txt", "h1", 2), e("b.txt", "hNEW", 9), e("new.txt", "h4", 5));

        DiffEngine.DiffResult diff = new DiffEngine().diff(from, to);

        assertEquals(1, diff.added());
        assertEquals(1, diff.modified());
        assertEquals(1, diff.deleted());
        assertEquals(3, diff.actions().size());  // PutFile(b), PutFile(new), Remove(gone)

        assertTrue(diff.actions().stream().anyMatch(a -> a instanceof RestoreAction.Remove r && r.path().equals("gone.txt")));
        assertTrue(diff.actions().stream().anyMatch(a -> a instanceof RestoreAction.PutFile p && p.path().equals("new.txt")));
    }

    @Test
    void detectsRenameByContent() {
        List<ManifestEntry> from = List.of(e("old/name.txt", "SAME", 5));
        List<ManifestEntry> to = List.of(e("new/name.txt", "SAME", 5));

        DiffEngine.DiffResult diff = new DiffEngine().diff(from, to);

        assertEquals(1, diff.renamed());
        assertEquals(0, diff.deleted(), "renamed files must not be classified as deleted");
        assertTrue(diff.changes().stream().anyMatch(c -> c.kind() == DiffEngine.Change.ChangeKind.RENAMED &&
            c.path().equals("new/name.txt")));
        // The new path must be materialized (PutFile action).
        assertTrue(diff.actions().stream().anyMatch(a -> a instanceof RestoreAction.PutFile p &&
            p.path().equals("new/name.txt")));
    }

    @Test
    void identicalSnapshotsProduceNoActions() {
        List<ManifestEntry> same = List.of(e("a.txt", "h1", 2), e("b.txt", "h2", 3));
        DiffEngine.DiffResult diff = new DiffEngine().diff(same, same);
        assertEquals(0, diff.actions().size());
        assertEquals(0, diff.total());
    }

    @Test
    void directoriesNeverProduceActionsAndSymlinksUsePutSymlink() {
        ManifestEntry dir = new ManifestEntry("lib", ManifestEntry.EntryKind.DIRECTORY, 0, null, null, null, 0);
        ManifestEntry linkOld = new ManifestEntry("link", ManifestEntry.EntryKind.SYMLINK, 0, null, "t1", null, 0);
        ManifestEntry linkNew = new ManifestEntry("link", ManifestEntry.EntryKind.SYMLINK, 0, null, "t2", null, 0);
        ManifestEntry addDir = new ManifestEntry("fresh", ManifestEntry.EntryKind.DIRECTORY, 0, null, null, null, 0);

        DiffEngine.DiffResult diff = new DiffEngine().diff(
            List.of(dir, e("a.txt", "h1", 2), linkOld),
            List.of(dir, e("a.txt", "h1", 2), linkNew, addDir));

        assertEquals(1, diff.actions().size(), "only the changed symlink should produce an action");
        assertTrue(diff.actions().get(0) instanceof RestoreAction.PutSymlink ps && ps.path().equals("link"));
        assertEquals(1, diff.modified());
        assertEquals(0, diff.added());
        assertEquals(0, diff.deleted());
        assertTrue(diff.actions().stream().noneMatch(a -> a.path().startsWith("lib")),
            "directory entries must never become PutFile actions");
    }
}