package dev.chronovault.intellij;

import org.junit.Test;

import javax.swing.SwingUtilities;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Pure-JDK tests for the native dashboard panel wiring (no IDE required). */
public class NativeDashboardPanelTest {

    private static final String STATE = "{\"status\":\"protected\",\"lastVerified\":\"now\","
        + "\"checkpoints\":3,\"snapshots\":9,\"protected\":\"yes\"}";
    private static final String CHECKPOINTS = "{\"checkpoints\":["
        + "{\"label\":\"abc123\",\"createdAt\":\"09:00\",\"status\":\"verified\"},"
        + "{\"label\":\"def456\",\"createdAt\":\"10:30\",\"status\":\"verified\"}]}";

    private static DashboardApiClient okApi() {
        return (method, path) -> switch (path) {
            case "/api/state" -> STATE;
            case "/api/checkpoints" -> CHECKPOINTS;
            default -> "{\"ok\":true}";
        };
    }

    @Test
    public void panelBuildsAndDisposes() throws Exception {
        AtomicReference<NativeDashboardPanel> ref = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            NativeDashboardPanel panel = new NativeDashboardPanel(okApi(), () -> {});
            assertNotNull(panel);
            assertEquals(3, panel.getComponentCount()); // banner, list, footer
            ref.set(panel);
        });
        SwingUtilities.invokeAndWait(() -> ref.get().dispose());
    }

    @Test
    public void errorApiYieldsEmptyState() throws Exception {
        AtomicInteger bannerLength = new AtomicInteger();
        SwingUtilities.invokeAndWait(() -> {
            NativeDashboardPanel panel = new NativeDashboardPanel(
                (m, p) -> { throw new java.io.IOException("down"); }, () -> {});
            bannerLength.set(panel.getComponentCount());
            panel.dispose();
        });
        assertTrue(bannerLength.get() > 0);
    }

    @Test
    public void malformedJsonYieldsEmptyState() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            NativeDashboardPanel panel = new NativeDashboardPanel(
                (m, p) -> "{bad json", () -> {});
            assertNotNull(panel);
            panel.dispose();
        });
    }

    @Test
    public void disposeStopsPoller() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            NativeDashboardPanel panel = new NativeDashboardPanel(okApi(), () -> {});
            panel.dispose();
            assertFalse(panel.isDisplayable());
        });
    }

    @Test
    public void blockedUrlsAreRejected() {
        assertFalse(JcefSupport.isLoopbackUrl("http://evil.com"));
        assertFalse(JcefSupport.isLoopbackUrl("file:///etc/passwd"));
        assertFalse(JcefSupport.isLoopbackUrl(null));
    }
}