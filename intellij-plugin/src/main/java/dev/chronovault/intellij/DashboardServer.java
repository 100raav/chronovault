package dev.chronovault.intellij;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages the local `chronovault ui --port N` dashboard server that backs the
 * embedded IntelliJ tool window. Loopback-only bind (the CLI binds 127.0.0.1),
 * pure JDK so it can be unit-tested without an IDE test harness.
 *
 * <p>Concurrency contract: {@link #start(StartOptions)} blocks up to
 * {@code readyTimeoutSeconds} (default 12s) while probing readiness, so call it
 * off the EDT (e.g. inside a background task). {@link #close()} is safe to call
 * from any thread.
 */
public final class DashboardServer implements AutoCloseable {

    private static final String LOOPBACK = "127.0.0.1";
    private static final int DEFAULT_READY_TIMEOUT_SECONDS = 12;

    private final Process process;
    private final int port;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private DashboardServer(Process process, int port) {
        this.process = process;
        this.port = port;
    }

    public int port() {
        return port;
    }

    public boolean isAlive() {
        return !closed.get() && process.isAlive();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            process.destroy();
            try {
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }

    /** Options used to start the dashboard server. */
    public static final class StartOptions {
        public String cli;
        public String projectRoot;
        public int port = -1;
        public long readyTimeoutSeconds = DEFAULT_READY_TIMEOUT_SECONDS;

        public StartOptions cli(@NotNull String cli) {
            this.cli = cli;
            return this;
        }

        public StartOptions projectRoot(@Nullable String projectRoot) {
            this.projectRoot = projectRoot;
            return this;
        }

        public StartOptions port(int port) {
            this.port = port;
            return this;
        }

        public StartOptions readyTimeoutSeconds(long readyTimeoutSeconds) {
            this.readyTimeoutSeconds = readyTimeoutSeconds;
            return this;
        }
    }

    /** Start the CLI dashboard server and wait until the API is responsive. */
    @NotNull
    public static DashboardServer start(@NotNull StartOptions opts) throws IOException {
        if (opts.cli == null || opts.cli.isBlank()) {
            throw new IOException("chronovault CLI runtime not configured");
        }
        int port = opts.port > 0 ? opts.port : findFreePort();
        List<String> cmd = buildCommand(opts.cli, port);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (opts.projectRoot != null) {
            pb.directory(Path.of(opts.projectRoot).toFile());
        }
        pb.environment().put("CHRONOVAULT_PROJECT", opts.projectRoot == null ? "" : opts.projectRoot);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        DashboardServer server = new DashboardServer(p, port);
        boolean ready = false;
        try {
            ready = waitUntilReady(port, opts.readyTimeoutSeconds);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        if (!ready) {
            server.close();
            throw new IOException("chronovault dashboard server did not become ready on port " + port);
        }
        return server;
    }

    /** Build the exact external process command: {@code <cli> ui --port <port>}. */
    static List<String> buildCommand(String cli, int port) {
        List<String> cmd = new ArrayList<>();
        cmd.add(cli);
        cmd.add("ui");
        cmd.add("--port");
        cmd.add(Integer.toString(port));
        return cmd;
    }

    /** Pick a currently-free loopback port on the ephemeral range. */
    public static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, java.net.InetAddress.getByName(LOOPBACK))) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    /** Probe {@code /api/state} every 250ms until the server answers 2xx or the
     * deadline passes. A socket success on an empty reply still counts as ready. */
    static boolean waitUntilReady(int port, long timeoutSeconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(1, timeoutSeconds));
        while (System.nanoTime() < deadline) {
            if (probe(port)) {
                return true;
            }
            Thread.sleep(250);
        }
        return false;
    }

    static boolean probe(int port) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create("http://" + LOOPBACK + ":" + port + "/api/state").toURL().openConnection();
            conn.setConnectTimeout(800);
            conn.setReadTimeout(800);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            conn.disconnect();
            return code >= 200 && code < 500;
        } catch (IOException ioe) {
            return false;
        }
    }

    /** True when the loopback port is currently accepting TCP connections. */
    static boolean isPortOpen(int port) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new InetSocketAddress(LOOPBACK, port), 500);
            return true;
        } catch (IOException ioe) {
            return false;
        }
    }
}