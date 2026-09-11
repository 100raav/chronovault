package dev.chronovault.core.storage;

import dev.chronovault.core.util.Compression;
import dev.chronovault.core.util.Hashing;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * Content-addressed object store.
 * Objects are stored gzip-compressed under vault/objects/aa/bb/<full-sha256>.
 * The hash is computed over the raw (uncompressed) content.
 */
public final class DiskContentStore implements ContentStore {
    private final Path objectsRoot;

    public DiskContentStore(Path objectsRoot) throws IOException {
        this.objectsRoot = objectsRoot;
        Files.createDirectories(objectsRoot);
    }

    private Path objectPath(String hash) {
        return objectsRoot.resolve(hash.substring(0, 2))
            .resolve(hash.substring(2, 4))
            .resolve(hash);
    }

    @Override
    public String put(Path sourceFile) throws IOException {
        String hash = Hashing.hashFile(sourceFile);
        Path dest = objectPath(hash);
        if (Files.exists(dest)) {
            return hash;
        }
        Files.createDirectories(dest.getParent());
        Path tmp = Files.createTempFile(dest.getParent(), ".tmp-", ".gz");
        try {
            Compression.gzipTo(sourceFile, tmp);
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Files.deleteIfExists(tmp);
            throw new IOException("Failed to store object " + hash, e);
        }
        return hash;
    }

    @Override
    public Optional<Path> materialize(String contentHash, Path dest) throws IOException {
        Path src = objectPath(contentHash);
        if (!Files.exists(src)) {
            return Optional.empty();
        }
        Files.createDirectories(dest.getParent());
        Path tmp = Files.createTempFile(dest.getParent(), ".tmp-", ".restore");
        try {
            verifyContent(src, tmp);
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return Optional.of(dest);
        } catch (Exception e) {
            Files.deleteIfExists(tmp);
            throw new IOException("Failed to materialize object " + contentHash, e);
        }
    }

    private void verifyContent(Path src, Path tmp) throws IOException {
        Compression.gunzipTo(src, tmp);
        String check = Hashing.hashFile(tmp);
        String expected = src.getFileName().toString();
        if (!check.equals(expected)) {
            throw new IOException("Object integrity check failed: expected " + expected + " got " + check);
        }
    }

    @Override
    public boolean contains(String contentHash) throws IOException {
        return Files.exists(objectPath(contentHash));
    }

    @Override
    public long physicalBytes() throws IOException {
        long[] total = {0};
        walk(objectsRoot, p -> {
            if (Files.isRegularFile(p)) {
                try { total[0] += Files.size(p); } catch (IOException ignored) {}
            }
        });
        return total[0];
    }

    @Override
    public long count() throws IOException {
        long[] total = {0};
        walk(objectsRoot, p -> {
            if (Files.isRegularFile(p)) total[0]++;
        });
        return total[0];
    }

    @Override
    public boolean verify(String contentHash) throws IOException {
        Path src = objectPath(contentHash);
        if (!Files.exists(src)) return false;
        Path tmp = Files.createTempFile("cv-verify", ".gz");
        try {
            try {
                Compression.gunzipTo(src, tmp);
                String check = Hashing.hashFile(tmp);
                return check.equals(contentHash);
            } catch (IOException e) {
                return false;  // corrupted or truncated object: verification fails cleanly
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Override
    public void delete(Path dest) throws IOException {
        Files.deleteIfExists(dest);
    }

    private interface FileVisitor { void visit(Path p) throws IOException; }

    private void walk(Path root, FileVisitor visitor) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path p : stream) {
                if (Files.isDirectory(p)) {
                    walk(p, visitor);
                } else {
                    visitor.visit(p);
                }
            }
        }
    }

    public Path getObjectPath(String hash) {
        return objectPath(hash);
    }

    public Path getObjectsRoot() {
        return objectsRoot;
    }
}