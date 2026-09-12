package dev.chronovault.core.snapshot;

import dev.chronovault.core.domain.*;
import dev.chronovault.core.project.ProjectContext;
import dev.chronovault.core.storage.ContentStore;
import dev.chronovault.core.util.IgnoreMatcher;
import dev.chronovault.core.util.PathSafety;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public final class DefaultSnapshotEngine implements SnapshotEngine {

    public static final int MAX_PARALLEL = Math.min(8, Math.max(2, Runtime.getRuntime().availableProcessors()));

    @Override
    public SnapshotManifest createSnapshot(ProjectContext ctx, Consumer<String> progressCallback) throws IOException {
        return createSnapshotInternal(ctx, null, progressCallback);
    }

    @Override
    public SnapshotManifest createIncremental(ProjectContext ctx, SnapshotManifest parent,
                                              Consumer<String> progressCallback) throws IOException {
        return createSnapshotInternal(ctx, parent, progressCallback);
    }

    public SnapshotManifest createSnapshotInternal(ProjectContext ctx, SnapshotManifest parent,
                                                   Consumer<String> progressCallback) throws IOException {
        Path root = ctx.root();
        VaultConfig config = ctx.config();
        IgnoreMatcher ignore = new IgnoreMatcher(config.ignorePatterns());
        ContentStore contentStore = ctx.contentStore();

        if (progressCallback != null) progressCallback.accept("Scanning project files…");

        List<ManifestEntry> entries = Collections.synchronizedList(new ArrayList<>());
        long[] logicalBytes = {0};
        long[] fileCount = {0};
        List<String> unreadable = Collections.synchronizedList(new ArrayList<>());
        Map<String, ManifestEntry> parentByPath = new HashMap<>();
        if (parent != null) {
            for (ManifestEntry e : parent.entries()) parentByPath.put(e.path(), e);
        }

        ExecutorService pool = Executors.newFixedThreadPool(MAX_PARALLEL, r -> {
            Thread t = new Thread(r, "cv-hash");
            t.setDaemon(true);
            return t;
        });

        try {
            List<Future<?>> futures = new ArrayList<>();
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (!dir.equals(root) && ignore.isIgnored(dir, root)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    String rel = toRel(root, dir);
                    if (!rel.isEmpty()) {
                        entries.add(new ManifestEntry(rel, ManifestEntry.EntryKind.DIRECTORY, 0, null, null, null, 0));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (ignore.isIgnored(file, root)) return FileVisitResult.CONTINUE;
                    String rel = toRel(root, file);
                    if (rel.isEmpty()) return FileVisitResult.CONTINUE;
                    if (Files.isSymbolicLink(file)) {
                        String target = Files.readSymbolicLink(file).toString();
                        entries.add(new ManifestEntry(rel, ManifestEntry.EntryKind.SYMLINK, 0, null, target, null, attrs.lastModifiedTime().toMillis()));
                        return FileVisitResult.CONTINUE;
                    }
                    long size = attrs.size();
                    ManifestEntry pe = parentByPath.get(rel);
                    if (pe != null && pe.kind() == ManifestEntry.EntryKind.FILE
                            && pe.contentHash() != null
                            && pe.size() == size
                            && pe.mtimeMillis() == attrs.lastModifiedTime().toMillis()) {
                        // Unchanged since the parent snapshot — reuse its content reference.
                        fileCount[0]++;
                        logicalBytes[0] += size;
                        entries.add(new ManifestEntry(rel, ManifestEntry.EntryKind.FILE, size,
                            pe.contentHash(), null, pe.permissions(), pe.mtimeMillis()));
                        return FileVisitResult.CONTINUE;
                    }
                    fileCount[0]++;
                    logicalBytes[0] += size;
                    synchronized (futures) {
                        futures.add(pool.submit(() -> {
                            try {
                                String hash = contentStore.put(file);
                                Set<PosixFilePermission> perms = readPermissions(file);
                                entries.add(new ManifestEntry(rel, ManifestEntry.EntryKind.FILE, size, hash,
                                    null, perms, attrs.lastModifiedTime().toMillis()));
                            } catch (IOException e) {
                                throw new CompletionException("Failed to hash " + rel, e);
                            }
                        }));
                    }
                    if (fileCount[0] % 100 == 0 && progressCallback != null) {
                        progressCallback.accept("Hashing… " + fileCount[0] + " files");
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    unreadable.add(file + " (" + exc.getMessage() + ")");
                    return FileVisitResult.CONTINUE;
                }
            });

            for (Future<?> f : futures) {
                f.get();
            }

            if (!unreadable.isEmpty()) {
                String sample = unreadable.stream().limit(5).collect(java.util.stream.Collectors.joining("; "));
                throw new IOException("Snapshot failed — " + unreadable.size() +
                    " file(s) unreadable: " + sample);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Snapshot interrupted", e);
        } catch (ExecutionException e) {
            throw new IOException("Snapshot failed: " + e.getCause().getMessage(), e.getCause());
        } finally {
            pool.shutdownNow();
        }

        List<ManifestEntry> sorted = entries.stream()
            .sorted(Comparator.comparing(ManifestEntry::path))
            .toList();

        SnapshotManifest manifest = new SnapshotManifest(
            SnapshotId.generate(),
            ctx.projectId(),
            parent != null ? SnapshotManifest.SnapshotType.INCREMENTAL : SnapshotManifest.SnapshotType.FULL,
            parent != null ? parent.snapshotId() : null,
            Instant.now(),
            sorted,
            logicalBytes[0],
            contentStore.physicalBytes(),
            fileCount[0]
        );

        ctx.metadataStore().saveSnapshotManifest(manifest);
        if (progressCallback != null) progressCallback.accept("Snapshot complete — " + fileCount[0] + " files");
        return manifest;
    }

    @Override
    public long restoreSnapshot(ProjectContext ctx, SnapshotManifest manifest, boolean strict) throws IOException {
        Path root = ctx.root();
        ContentStore contentStore = ctx.contentStore();
        long restored = 0;

        Set<String> manifestPaths = new HashSet<>();
        for (ManifestEntry entry : manifest.entries()) {
            manifestPaths.add(entry.path());
            Path target = PathSafety.resolveInside(root, entry.path());
            switch (entry.kind()) {
                case FILE -> {
                    PathSafety.validateWritePath(target, root);
                    if (Files.isSymbolicLink(target)) {
                        Files.deleteIfExists(target);
                    }
                    Optional<Path> materialized = contentStore.materialize(entry.contentHash(), target);
                    if (materialized.isPresent()) {
                        applyPermissions(target, entry.permissions());
                        restored++;
                    }
                }
                case SYMLINK -> {
                    PathSafety.validateWritePath(target, root);
                    Files.deleteIfExists(target);
                    Files.createSymbolicLink(target, Path.of(entry.symlinkTarget()));
                    restored++;
                }
                case DIRECTORY -> PathSafety.validateDirectory(target, root);
            }
        }

        if (strict) {
            IgnoreMatcher ignore = new IgnoreMatcher(ctx.config().ignorePatterns());
            List<Path> extra = new ArrayList<>();
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(root) && (dir.getFileName().toString().equals(".chronovault")
                            || ignore.isIgnored(dir, root))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String rel = toRel(root, file);
                    if (!rel.isEmpty() && !ignore.isIgnored(file, root) && !manifestPaths.contains(rel)) {
                        extra.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
            for (Path p : extra) {
                Files.deleteIfExists(p);
                restored++;
            }
        }
        return restored;
    }

    private Set<PosixFilePermission> readPermissions(Path file) {
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
            return perms.isEmpty() ? null : perms;
        } catch (UnsupportedOperationException | IOException e) {
            return null;
        }
    }

    private void applyPermissions(Path file, Set<PosixFilePermission> perms) {
        if (perms == null) return;
        try {
            Files.setPosixFilePermissions(file, perms);
        } catch (UnsupportedOperationException | IOException ignored) {}
    }

    private String toRel(Path root, Path file) {
        return PathSafety.normalizeSlash(root.relativize(file).toString());
    }
}