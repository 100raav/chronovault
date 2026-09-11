package dev.chronovault.core.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class Hashing {
    private Hashing() {}

    public static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String hashBytes(byte[] data) {
        return HexFormat.of().formatHex(sha256().digest(data));
    }

    public static String hashString(String data) {
        return hashBytes(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public static String hashFile(Path file) throws IOException {
        MessageDigest md = sha256();
        byte[] buffer = new byte[1 << 16];
        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.read(buffer)) != -1) {
                md.update(buffer, 0, n);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }
}