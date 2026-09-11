package dev.chronovault.core.util;

import java.time.Instant;
import java.util.Optional;

public final class Sizes {
    private Sizes() {}

    public static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format("%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format("%.1f MB", mb);
        double gb = mb / 1024.0;
        return String.format("%.2f GB", gb);
    }

    public static String time(Instant instant) {
        if (instant == null) return "-";
        return instant.toString().replace("T", " ").replace("Z", "").substring(0, 19);
    }
}