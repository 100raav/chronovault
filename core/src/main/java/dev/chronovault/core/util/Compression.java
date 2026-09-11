package dev.chronovault.core.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class Compression {
    private Compression() {}

    public static void gzipTo(Path source, Path dest) throws IOException {
        try (InputStream in = Files.newInputStream(source);
             GZIPOutputStream gz = new GZIPOutputStream(Files.newOutputStream(dest))) {
            in.transferTo(gz);
        }
    }

    public static void gunzipTo(Path source, Path dest) throws IOException {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(source));
             OutputStream out = Files.newOutputStream(dest)) {
            in.transferTo(out);
        }
    }

    public static long compressedSize(Path source) throws IOException {
        Path tmp = Files.createTempFile("cv-comp", ".gz");
        try {
            gzipTo(source, tmp);
            return Files.size(tmp);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}