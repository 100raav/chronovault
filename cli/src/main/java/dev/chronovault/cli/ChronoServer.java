package dev.chronovault.cli;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.chronovault.core.application.ChronoVault;
import dev.chronovault.core.domain.*;
import dev.chronovault.core.protocol.EventBus;
import dev.chronovault.core.recovery.RecoveryService;
import dev.chronovault.core.util.JsonUtil;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Embedded HTTP server powering the CHRONOVAULT temporal dashboard.
 * Serves the built-in futuristic UI as static assets plus a JSON/REST + SSE
 * protocol backed entirely by real core operations.
 */
public final class ChronoServer {
    public static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
        .withZone(ZoneId.systemDefault());

    private final ChronoVault vault;
    private final int port;
    private HttpServer server;
    private final List<Consumer<OperationUpdate>> sseClients = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor();
    private static final int SSE_HEARTBEAT_SECONDS = 15;

    public ChronoServer(ChronoVault vault, int port) {
        this.vault = vault;
        this.port = port;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();

        vault.eventBus().subscribe(this::dispatchToSSE);
        ticker.scheduleAtFixedRate(() -> {
            for (Consumer<OperationUpdate> c : sseClients) {
                try {
                    OperationUpdate beat = new OperationUpdate(
                        OperationId.of("beat"), OperationStage.UNKNOWN, -1, ": keep-alive", java.time.Instant.now());
                    c.accept(beat);
                } catch (Exception ignored) {}
            }
        }, SSE_HEARTBEAT_SECONDS, SSE_HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    /** Restricts cross-origin access to loopback origins. */
    private void cors(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin != null && (origin.startsWith("http://localhost:")
                || origin.startsWith("http://127.0.0.1:")
                || origin.startsWith("http://[::1]:"))) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
        }
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
    }

    public void stop() {
        if (server != null) server.stop(0);
        ticker.shutdownNow();
        vault.eventBus().unsubscribe(this::dispatchToSSE);
    }

    private void dispatchToSSE(OperationUpdate update) {
        for (Consumer<OperationUpdate> c : sseClients) {
            try { c.accept(update); } catch (Exception ignored) {}
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getQuery();
        Map<String, String> q = parseQuery(query);
        String method = exchange.getRequestMethod();

        try {
            if (method.equals("GET") && path.equals("/api/state")) { json(exchange, state()); }
            else if (method.equals("GET") && path.equals("/api/meta")) { json(exchange, meta()); }
            else if (method.equals("GET") && path.equals("/api/checkpoints")) { json(exchange, vault.checkpointService().list(vault.projectContext())); }
            else if (method.equals("GET") && path.startsWith("/api/checkpoint/")) {
                String id = path.substring("/api/checkpoint/".length());
                json(exchange, vault.checkpoint(CheckpointId.of(id)).orElse(null));
            }
            else if (method.equals("POST") && path.equals("/api/checkpoint")) {
                json(exchange, asyncOp("CREATE_CHECKPOINT", prog -> {
                    try {
                        HealthResult health = vault.runHealth(prog);
                        Checkpoint cp = vault.createSnapshotOnly(null, prog);
                        if (health.overallPass()) {
                            vault.checkpointService().setStatus(vault.projectContext(), cp.id(), CheckpointStatus.VERIFIED);
                        } else {
                            vault.checkpointService().setStatus(vault.projectContext(), cp.id(), CheckpointStatus.BROKEN);
                        }
                        return cp;
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));
            }
            else if (method.equals("POST") && path.equals("/api/health")) {
                json(exchange, asyncOp("RUN_HEALTH", prog -> vault.runHealth(prog)));
            }
            else if (method.equals("POST") && path.equals("/api/recover")) {
                String to = q.get("to");
                String verifyStr = q.getOrDefault("verify", "true");
                boolean verify = Boolean.parseBoolean(verifyStr);
                String pathsParam = q.get("paths");
                if (to == null || to.isBlank()) {
                    Optional<Checkpoint> last = vault.checkpointService().findLastVerified(vault.projectContext());
                    if (last.isEmpty()) throw new IllegalArgumentException("No verified checkpoint available");
                    to = last.get().id().value();
                }
                String finalTo = to;
                java.util.List<String> paths = (pathsParam == null || pathsParam.isBlank()) ? null
                    : java.util.Arrays.stream(pathsParam.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
                json(exchange, asyncOpStage("RECOVER", stageProgress -> {
                    try {
                        if (paths != null && !paths.isEmpty()) {
                            return vault.executeRecovery(CheckpointId.of(finalTo),
                                p -> { for (String s : paths) if (p.equals(s) || p.startsWith(s + "/")) return true; return false; },
                                stageProgress, verify);
                        }
                        return vault.executeRecovery(CheckpointId.of(finalTo), stageProgress, verify);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));
            }
            else if (method.equals("GET") && path.equals("/api/plan")) {
                String to = q.get("to");
                Checkpoint target;
                if (to == null || to.isBlank()) {
                    target = vault.checkpointService().findLastVerified(vault.projectContext())
                        .orElseThrow(() -> new IllegalArgumentException("No verified checkpoint"));
                } else {
                    target = vault.checkpoint(CheckpointId.of(to))
                        .orElseThrow(() -> new IllegalArgumentException("Checkpoint not found: " + to));
                }
                var planned = vault.recoveryService().planRecovery(vault.projectContext(), target.id(), (s, m) -> {});
                json(exchange, restorePlanView(planned.plan()));
            }
            else if (method.equals("GET") && path.equals("/api/diff")) {
                json(exchange, compare(q));
            }
            else if (method.equals("GET") && path.equals("/api/diagnose")) {
                json(exchange, vault.diagnose(true, msg -> {}));
            }
            else if (method.equals("GET") && path.equals("/api/history")) {
                json(exchange, vault.operationHistory());
            }
            else if (method.equals("GET") && path.equals("/api/storage")) {
                json(exchange, vault.storageStats());
            }
            else if (method.equals("GET") && path.equals("/api/events")) {
                sse(exchange);
            }
            else if (path.equals("/") || path.equals("/index.html") || path.equals("/app") || path.equals("/favicon.ico")) {
                if (path.equals("/favicon.ico")) { staticFile(exchange, "web/icon.svg", "image/svg+xml"); return; }
                staticFile(exchange, "web/index.html", "text/html; charset=utf-8");
            }
            else if (path.startsWith("/static/")) {
                staticAsset(exchange, path.substring("/static/".length()));
            }
            else {
                exchange.sendResponseHeaders(404, 0);
                exchange.close();
            }
        } catch (IllegalArgumentException e) {
            error(exchange, 400, e.getMessage());
        } catch (Exception e) {
            error(exchange, 500, e.getMessage() == null ? "internal error" : e.getMessage());
        }
    }

    private Object meta() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "CHRONOVAULT");
        m.put("version", "1.0.0");
        m.put("project", vault.projectContext().root().toString());
        m.put("projectName", vault.projectContext().name());
        m.put("tagline", "Return to the moment your code still worked.");
        return m;
    }

    private Map<String, Object> state() throws IOException {
        List<Checkpoint> cps = vault.checkpoints();
        Optional<Checkpoint> last = vault.checkpointService().findLastVerified(vault.projectContext());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("project", vault.projectContext().root().toString());
        m.put("projectName", vault.projectContext().name());
        m.put("checkpointCount", cps.size());
        m.put("lastVerified", last.orElse(null));
        m.put("storage", vault.storageStats());
        m.put("serverTime", java.time.Instant.now().toString());
        return m;
    }

    private Object compare(Map<String, String> q) throws IOException {
        String fromId = q.get("from");
        String toId = q.get("to");
        List<Checkpoint> cps = vault.checkpoints();
        if (cps.isEmpty()) return Map.of("count", 0, "changes", List.of());
        Checkpoint cpA = fromId != null
            ? find(cps, fromId).orElseThrow(() -> new IllegalArgumentException("Unknown from checkpoint"))
            : cps.stream().filter(c -> c.evidence() != null && c.evidence().passed()).findFirst().orElse(cps.get(cps.size() - 1));
        Checkpoint cpB = toId != null
            ? find(cps, toId).orElseThrow(() -> new IllegalArgumentException("Unknown to checkpoint"))
            : cps.get(0);
        var mA = vault.metaStore().getSnapshotManifest(vault.projectContext().projectId(), cpA.snapshotId()).orElseThrow();
        var mB = vault.metaStore().getSnapshotManifest(vault.projectContext().projectId(), cpB.snapshotId()).orElseThrow();
        var diff = vault.diffEngine().diff(mA.entries(), mB.entries());
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("from", cpA.id());
        r.put("to", cpB.id());
        r.put("added", diff.added());
        r.put("modified", diff.modified());
        r.put("deleted", diff.deleted());
        r.put("renamed", diff.renamed());
        r.put("changes", diff.changes());
        return r;
    }

    private Optional<Checkpoint> find(List<Checkpoint> cps, String id) {
        return cps.stream().filter(c -> c.id().value().equals(id) || c.shortId().equals(id)).findFirst();
    }

    /** Serializes a RestorePlan with a stable `type` discriminator on each action. */
    private static Map<String, Object> restorePlanView(RestorePlan plan) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("targetCheckpointId", plan.targetCheckpointId());
        view.put("sourceSnapshotId", plan.sourceSnapshotId());
        view.put("targetSnapshotId", plan.targetSnapshotId());
        view.put("projectId", plan.projectId());
        view.put("protectiveSnapshotId", plan.protectiveSnapshotId());
        view.put("modifiedFiles", plan.modifiedFiles());
        view.put("addedFiles", plan.addedFiles());
        view.put("deletedFiles", plan.deletedFiles());
        view.put("renamedFiles", plan.renamedFiles());
        view.put("totalBytes", plan.totalBytes());
        view.put("dependencyChanges", plan.dependencyChanges());
        List<Map<String, Object>> actions = new ArrayList<>();
        for (RestoreAction a : plan.actions()) {
            Map<String, Object> am = new LinkedHashMap<>();
            am.put("type", switch (a) {
                case RestoreAction.PutFile ignored -> "PUT";
                case RestoreAction.PutSymlink ignored -> "SYMLINK";
                case RestoreAction.Remove ignored -> "REMOVE";
            });
            am.put("path", a.path());
            if (a instanceof RestoreAction.PutFile pf) {
                am.put("contentHash", pf.contentHash());
                am.put("size", pf.size());
            }
            if (a instanceof RestoreAction.PutSymlink ps) am.put("target", ps.target());
            actions.add(am);
        }
        view.put("actions", actions);
        return view;
    }

    // --------------------------------------------------------------- async ops

    private Object asyncOp(String kind, java.util.function.Function<Consumer<String>, Object> task) {
        OperationId opId = OperationId.generate();
        new Thread(() -> {
            Consumer<OperationUpdate> pub = u -> vault.eventBus().publish(u);
            Consumer<String> prog = msg -> pub.accept(new OperationUpdate(opId, OperationStage.UNKNOWN, -1, msg, java.time.Instant.now()));
            try {
                Object result = task.apply(msg -> pub.accept(new OperationUpdate(opId, OperationStage.UNKNOWN, -1, msg, java.time.Instant.now())));
                pub.accept(new OperationUpdate(opId, OperationStage.COMPLETED, 100, "done", java.time.Instant.now()));
                Map<String, Object> done = new LinkedHashMap<>();
                done.put("operationId", opId.value());
                done.put("status", "COMPLETED");
                done.put("result", result);
                pub.accept(new OperationUpdate(opId, OperationStage.UNKNOWN, 100,
                    JsonUtil.toJson(done), java.time.Instant.now()));
            } catch (Exception e) {
                pub.accept(new OperationUpdate(opId, OperationStage.FAILED, 0,
                    "ERROR: " + e.getMessage(), java.time.Instant.now()));
            }
        }, "cv-ui-" + opId).start();
        return Map.of("operationId", opId.value(), "status", "STARTED", "kind", kind);
    }

    /** Like {@link #asyncOp} but forwards the real, stage-carrying progress, so the
     *  dashboard can drive a recovery wizard from genuinely emitted operation stages. */
    private Object asyncOpStage(String kind,
                                java.util.function.Function<java.util.function.BiConsumer<OperationStage, String>, Object> task) {
        OperationId opId = OperationId.generate();
        new Thread(() -> {
            Consumer<OperationUpdate> pub = u -> vault.eventBus().publish(u);
            java.util.function.BiConsumer<OperationStage, String> stageProgress =
                (s, m) -> pub.accept(new OperationUpdate(opId, s, -1, m, java.time.Instant.now()));
            try {
                Object result = task.apply(stageProgress);
                Map<String, Object> done = new LinkedHashMap<>();
                done.put("operationId", opId.value());
                done.put("result", result);
                if (result instanceof RecoveryService.RecoveryOutcome outcome) {
                    // RecoveryService already published the terminal stage; mirror it in
                    // the result so the wizard never sees a false success.
                    done.put("status", outcome.finalStage().name());
                } else {
                    pub.accept(new OperationUpdate(opId, OperationStage.COMPLETED, 100, "done", java.time.Instant.now()));
                    done.put("status", "COMPLETED");
                }
                pub.accept(new OperationUpdate(opId, OperationStage.UNKNOWN, 100,
                    JsonUtil.toJson(done), java.time.Instant.now()));
            } catch (Exception e) {
                pub.accept(new OperationUpdate(opId, OperationStage.FAILED, 0,
                    "ERROR: " + e.getMessage(), java.time.Instant.now()));
            }
        }, "cv-ui-" + opId).start();
        return Map.of("operationId", opId.value(), "status", "STARTED", "kind", kind);
    }

    // --------------------------------------------------------------- SSE

    private void sse(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        cors(exchange);
        try {
            exchange.sendResponseHeaders(200, 0);
        } catch (IOException e) {
            exchange.close();
            return;
        }
        final OutputStream os = exchange.getResponseBody();
        final Consumer<OperationUpdate> client = update -> {
            try {
                StringBuilder sb = new StringBuilder();
                sb.append("data: ").append(JsonUtil.toJson(update)).append("\n\n");
                synchronized (os) {
                    os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
            } catch (IOException ignored) {
                throw new RuntimeException("sse closed");
            }
        };
        Consumer<OperationUpdate> tracked = new Consumer<>() {
            @Override
            public void accept(OperationUpdate update) {
                try {
                    client.accept(update);
                } catch (RuntimeException e) {
                    sseClients.remove(this);
                }
            }
        };
        sseClients.add(tracked);
        exchange.getHttpContext().getAttributes();
        for (OperationUpdate u : vault.eventBus().latest(50)) {
            try { tracked.accept(u); } catch (Exception ignored) {}
        }
    }

    // --------------------------------------------------------------- IO helpers

    private Map<String, String> parseQuery(String query) {
        Map<String, String> m = new HashMap<>();
        if (query == null) return m;
        for (String pair : query.split("&")) {
            if (pair.isBlank()) continue;
            String[] kv = pair.split("=", 2);
            m.put(java.net.URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                kv.length > 1 ? java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "true");
        }
        return m;
    }

    private void json(HttpExchange exchange, Object body) throws IOException {
        byte[] bytes = JsonUtil.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        cors(exchange);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void error(HttpExchange exchange, int code, String message) throws IOException {
        byte[] bytes = JsonUtil.toJson(Map.of("error", message)).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        cors(exchange);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void staticFile(HttpExchange exchange, String resource, String contentType) throws IOException {
        InputStream in = getClass().getClassLoader().getResourceAsStream(resource);
        if (in == null) {
            exchange.sendResponseHeaders(404, 0);
            exchange.close();
            return;
        }
        byte[] bytes = in.readAllBytes();
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void staticAsset(HttpExchange exchange, String name) throws IOException {
        InputStream in = getClass().getClassLoader().getResourceAsStream("web/" + name);
        if (in == null) {
            exchange.sendResponseHeaders(404, 0);
            exchange.close();
            return;
        }
        byte[] bytes = in.readAllBytes();
        String ct = switch (name.substring(name.lastIndexOf('.') + 1)) {
            case "css" -> "text/css";
            case "js" -> "application/javascript";
            case "svg" -> "image/svg+xml";
            case "png" -> "image/png";
            case "woff2" -> "font/woff2";
            default -> "application/octet-stream";
        };
        exchange.getResponseHeaders().set("Content-Type", ct);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}