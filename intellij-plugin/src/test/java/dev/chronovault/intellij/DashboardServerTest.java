package dev.chronovault.intellij;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** Pure-JDK unit tests for the dashboard server lifecycle (no IDE test harness). */
public class DashboardServerTest {

    @Test
    public void findFreePortReturnsAnEphemeralPort() throws Exception {
        int a = DashboardServer.findFreePort();
        int b = DashboardServer.findFreePort();
        assertTrue(a > 0 && a < 65536);
        assertNotEquals("two free ports should differ", a, b);
    }

    @Test
    public void buildCommandOrdersCliUiAndPortArguments() {
        List<String> cmd = DashboardServer.buildCommand("/usr/local/bin/chronovault", 7723);
        assertEquals(List.of("/usr/local/bin/chronovault", "ui", "--port", "7723"), cmd);
    }

    @Test
    public void waitUntilReadyIsFalseWithoutAListener() throws Exception {
        int port = DashboardServer.findFreePort();
        assertFalse(DashboardServer.waitUntilReady(port, 1));
    }

    @Test
    public void isPortOpenIsFalseForAnUnusedPort() throws Exception {
        int port = DashboardServer.findFreePort();
        assertFalse(DashboardServer.isPortOpen(port));
    }

    @Test
    public void probeIsFalseForAnUnusedPort() throws Exception {
        int port = DashboardServer.findFreePort();
        assertFalse(DashboardServer.probe(port));
    }

    @Test
    public void startRejectsWhenCliIsBlank() throws Exception {
        try {
            DashboardServer.start(new DashboardServer.StartOptions()
                .cli("")
                .projectRoot(System.getProperty("user.dir"))
                .readyTimeoutSeconds(1));
            throw new AssertionError("expected an IOException");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("not configured"));
        }
    }

    @Test
    public void requestReturnsJsonBodyOnSuccess() throws Exception {
        withServer((server, port) -> {
            String body = server.request("GET", "/api/state");
            assertEquals("{\"status\":\"protected\"}", body);
        });
    }

    @Test
    public void requestSupportsPostAndMethodEcho() throws Exception {
        withServer((server, port) -> {
            String body = server.request("POST", "/api/checkpoint");
            assertTrue(body.contains("POST"));
        });
    }

    @Test
    public void requestThrowsOnHttpError() throws Exception {
        withServer((server, port) -> {
            try {
                server.request("GET", "/api/boom");
                throw new AssertionError("expected IOException for 500");
            } catch (java.io.IOException expected) {
                assertTrue(expected.getMessage().contains("500"));
            }
        });
    }

    /** Spin a throwaway loopback HttpServer and run the assertions, never leaving it open. */
    private static void withServer(ThrowingConsumer<DashboardServer, Integer> assertions) throws Exception {
        java.net.InetSocketAddress addr = new java.net.InetSocketAddress("127.0.0.1", 0);
        com.sun.net.httpserver.HttpServer http = com.sun.net.httpserver.HttpServer.create(addr, 0);
        http.createContext("/api/state", exchange -> {
            respond(exchange, 200, "{\"status\":\"protected\"}");
        });
        http.createContext("/api/checkpoint", exchange -> {
            respond(exchange, 200, "{\"done\":\"POST\"}");
        });
        http.createContext("/api/boom", exchange -> {
            respond(exchange, 500, "{\"error\":\"boom\"}");
        });
        http.start();
        try {
            int port = http.getAddress().getPort();
            DashboardServer server = DashboardServer.testInstance(port);
            assertions.accept(server, port);
        } finally {
            http.stop(0);
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int code, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @FunctionalInterface
    private interface ThrowingConsumer<A, B> {
        void accept(A a, B b) throws Exception;
    }
}