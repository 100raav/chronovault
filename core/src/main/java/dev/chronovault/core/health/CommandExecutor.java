package dev.chronovault.core.health;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public interface CommandExecutor {
    record CommandResult(
        int exitCode,
        String stdout,
        String stderr,
        long durationMs,
        boolean timedOut,
        boolean wasCancelled
    ) {
        public boolean success() { return exitCode == 0 && !timedOut && !wasCancelled; }
    }

    CommandResult execute(Path workingDir, List<String> command, Duration timeout) throws IOException;
}