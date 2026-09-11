package dev.chronovault.core.diff;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ContentDiff {

    public record Line(int lineA, int lineB, char kind, String text) {}

    public record FileDiff(String path, boolean binary, boolean changed, List<Line> lines) {}

    private static final int MAX_DIFF_LINES = 4000;
    private static final int MAX_FILE_BYTES = 8 * 1024 * 1024;

    public FileDiff diff(Path oldFile, Path newFile) {
        if (oldFile == null && newFile == null) {
            return new FileDiff("", false, false, List.of());
        }
        if (oldFile == null) {
            List<Line> lines = readLines(newFile).stream().map(l -> new Line(-1, 0, '+', l)).toList();
            return new FileDiff(String.valueOf(newFile), isBinary(newFile), true, lines);
        }
        if (newFile == null) {
            List<Line> lines = readLines(oldFile).stream().map(l -> new Line(0, -1, '-', l)).toList();
            return new FileDiff(String.valueOf(oldFile), isBinary(oldFile), true, lines);
        }

        boolean binary = isBinary(oldFile) || isBinary(newFile);
        List<String> oldLines = readLines(oldFile);
        List<String> newLines = readLines(newFile);

        if (binary) {
            boolean changed = binaryChanged(oldFile, newFile);
            return new FileDiff(String.valueOf(newFile), true, changed, List.of());
        }

        List<Line> hunks = compute(oldLines, newLines);
        boolean changed = !oldLines.equals(newLines);
        return new FileDiff(String.valueOf(newFile), false, changed, hunks);
    }

    private List<Line> compute(List<String> oldLines, List<String> newLines) {
        if (oldLines.isEmpty()) {
            List<Line> out = new ArrayList<>();
            for (int i = 0; i < newLines.size() && i < MAX_DIFF_LINES; i++) {
                out.add(new Line(-1, i, '+', newLines.get(i)));
            }
            return out;
        }
        if (newLines.isEmpty()) {
            List<Line> out = new ArrayList<>();
            for (int i = 0; i < oldLines.size() && i < MAX_DIFF_LINES; i++) {
                out.add(new Line(i, -1, '-', oldLines.get(i)));
            }
            return out;
        }

        // Trim common prefix.
        int prefix = 0;
        while (prefix < oldLines.size() && prefix < newLines.size()
            && oldLines.get(prefix).equals(newLines.get(prefix))) {
            prefix++;
        }
        // Trim common suffix.
        int suffix = 0;
        while (suffix < oldLines.size() - prefix && suffix < newLines.size() - prefix
            && oldLines.get(oldLines.size() - 1 - suffix).equals(newLines.get(newLines.size() - 1 - suffix))) {
            suffix++;
        }

        List<Line> out = new ArrayList<>();
        for (int i = 0; i < prefix && out.size() < MAX_DIFF_LINES; i++) {
            out.add(new Line(i, i, ' ', oldLines.get(i)));
        }

        List<String> oldMid = oldLines.subList(prefix, oldLines.size() - suffix);
        List<String> newMid = newLines.subList(prefix, newLines.size() - suffix);

        if (oldMid.size() + newMid.size() <= 3000) {
            // Myers-style edit script via LCS DP is O(N*M); for small middle sections we can afford O(N*M).
            lcsDiff(oldMid, newMid, prefix, prefix, out);
        } else {
            for (int i = 0; i < oldMid.size() && out.size() < MAX_DIFF_LINES; i++) {
                out.add(new Line(prefix + i, -1, '-', oldMid.get(i)));
            }
            for (int i = 0; i < newMid.size() && out.size() < MAX_DIFF_LINES; i++) {
                out.add(new Line(-1, prefix + i, '+', newMid.get(i)));
            }
        }

        for (int i = 0; i < suffix && out.size() < MAX_DIFF_LINES; i++) {
            int o = oldLines.size() - suffix + i;
            int n = newLines.size() - suffix + i;
            out.add(new Line(o, n, ' ', oldLines.get(o)));
        }
        return out;
    }

    private void lcsDiff(List<String> oldMid, List<String> newMid, int oldBase, int newBase,
                         List<Line> out) {
        int n = oldMid.size(), m = newMid.size();
        // Classic edit-distance DP (using only current/prev rows for memory).
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = j;
        for (int i = 1; i <= n; i++) {
            cur[0] = i;
            for (int j = 1; j <= m; j++) {
                if (oldMid.get(i - 1).equals(newMid.get(j - 1))) {
                    cur[j] = prev[j - 1];
                } else {
                    cur[j] = Math.min(prev[j], cur[j - 1]) + 1;
                }
            }
            int[] tmp = prev; prev = cur; cur = tmp;
        }
        // Backtrack.
        List<Line> back = new ArrayList<>();
        int i = n, j = m;
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && oldMid.get(i - 1).equals(newMid.get(j - 1))) {
                back.add(new Line(oldBase + i - 1, newBase + j - 1, ' ', oldMid.get(i - 1)));
                i--; j--;
            } else if (j > 0 && (i == 0 || prev[j - 1] <= prev[j])) {
                back.add(new Line(-1, newBase + j - 1, '+', newMid.get(j - 1)));
                j--;
            } else {
                back.add(new Line(oldBase + i - 1, -1, '-', oldMid.get(i - 1)));
                i--;
            }
        }
        for (int k = back.size() - 1; k >= 0 && out.size() < MAX_DIFF_LINES; k--) {
            out.add(back.get(k));
        }
    }

    private boolean binaryChanged(Path a, Path b) {
        try {
            if (!Files.isRegularFile(a)) return true;
            if (!Files.isRegularFile(b)) return true;
            long sa = Files.size(a), sb = Files.size(b);
            if (sa != sb) return true;
            try (java.io.InputStream ia = Files.newInputStream(a);
                 java.io.InputStream ib = Files.newInputStream(b)) {
                byte[] ba = new byte[8192];
                byte[] bb = new byte[8192];
                int na, nb;
                while ((na = ia.read(ba)) != -1) {
                    nb = ib.read(bb);
                    if (na != nb) return true;
                    for (int i = 0; i < na; i++) {
                        if (ba[i] != bb[i]) return true;
                    }
                }
                return ib.read(new byte[1]) != -1;
            }
        } catch (IOException e) {
            return true;
        }
    }

    private List<String> readLines(Path file) {
        if (file == null || !Files.isRegularFile(file)) return List.of();
        try {
            String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            return List.of(content.split("\n", -1));
        } catch (IOException | OutOfMemoryError e) {
            return List.of();
        }
    }

    private boolean isBinary(Path file) {
        if (file == null || !Files.isRegularFile(file)) return false;
        try {
            if (Files.size(file) > MAX_FILE_BYTES) return true;
            byte[] probe = Files.readAllBytes(file);
            int limit = Math.min(probe.length, 8000);
            for (int i = 0; i < limit; i++) {
                byte b = probe[i];
                if (b == 0) return true;
                if (b < 0x09 || (b > 0x0d && b < 0x20)) return true;
            }
            return false;
        } catch (IOException e) {
            return true;
        }
    }
}