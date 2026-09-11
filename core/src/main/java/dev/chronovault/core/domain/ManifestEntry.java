package dev.chronovault.core.domain;

import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

public record ManifestEntry(
    String path,
    EntryKind kind,
    long size,
    String contentHash,
    String symlinkTarget,
    Set<PosixFilePermission> permissions,
    long mtimeMillis
) {
    public enum EntryKind {
        FILE, SYMLINK, DIRECTORY
    }

    public boolean isRegularFile() { return kind == EntryKind.FILE; }
    public boolean isSymlink() { return kind == EntryKind.SYMLINK; }
}