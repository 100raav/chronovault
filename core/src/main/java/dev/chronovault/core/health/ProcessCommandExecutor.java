package dev.chronovault.core.health;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ProcessCommandExecutor implements CommandExecutor {
    public static final int MAX_CAPTURE_BYTES = 128 * 1024;

    private final AtomicBoolean canceled = new AtomicBoolean(false);
    private final SecretScrubber scrubber = new SecretScrubber();

    @Override
    public CommandResult execute(Path workingDir, List<String> command, Duration timeout) throws java.io.IOException {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("Command cannot be empty");
        }
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDir.toFile());
        pb.redirectErrorStream(false);

        long start = System.nanoTime();
        Process process = pb.start();

        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        Status status = new Status();

        Thread outThread = new Thread(() -> drain(process.getInputStream(), stdout, status));
        Thread errThread = new Thread(() -> drain(process.getErrorStream(), stderr, status));
        outThread.setDaemon(true);
        errThread.setDaemon(true);
        outThread.start();
        errThread.start();

        try {
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroy();
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
                status.timedOut = true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            status.cancelled = true;
        }

        try {
            outThread.join(2000);
            errThread.join(2000);
        } catch (InterruptedException ignored) {}

        long durationMs = (System.nanoTime() - start) / 1_000_000;

        return new CommandResult(
            status.timedOut || status.cancelled ? -1 : process.exitValue(),
            scrubber.scrub(stdout.toString()),
            scrubber.scrub(stderr.toString()),
            durationMs,
            status.timedOut,
            status.cancelled
        );
    }

    private void drain(java.io.InputStream in, StringBuilder sb, Status status) {
        byte[] buffer = new byte[4096];
        try {
            int n;
            int total = 0;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                if (total <= MAX_CAPTURE_BYTES) {
                    sb.append(new String(buffer, 0, n, java.nio.charset.StandardCharsets.UTF_8));
                } else if (sb.length() < MAX_CAPTURE_BYTES * 2) {
                    sb.append("…[output truncated]…\n");
                }
            }
        } catch (Exception ignored) {}
    }

    /** Static scratch holder for execution state. */
    private static final class Status {
        boolean timedOut;
        boolean cancelled;
    }
}