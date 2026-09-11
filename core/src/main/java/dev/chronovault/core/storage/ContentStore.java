package dev.chronovault.core.storage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

public interface ContentStore {
    String put(Path sourceFile) throws IOException;
    Optional<Path> materialize(String contentHash, Path dest) throws IOException;
    boolean contains(String contentHash) throws IOException;
    long physicalBytes() throws IOException;
    long count() throws IOException;
    boolean verify(String contentHash) throws IOException;
    void delete(Path dest) throws IOException;
}