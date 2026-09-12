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
}