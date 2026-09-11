package dev.chronovault.core.domain;

import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

public sealed interface RestoreAction {
    record PutFile(String path, String contentHash, long size, Set<PosixFilePermission> permissions)
        implements RestoreAction {}
    record PutSymlink(String path, String target) implements RestoreAction {}
    record Remove(String path) implements RestoreAction {}

    String path();
}