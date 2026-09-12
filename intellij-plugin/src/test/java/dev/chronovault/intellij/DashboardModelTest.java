package dev.chronovault.intellij;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Pure-JDK tests for the dashboard display model. */
public class DashboardModelTest {

    private static final String STATE = "{\"status\":\"protected\",\"lastVerified\":\"2026-09-12 14:02\","
        + "\"checkpoints\":3,\"snapshots\":9,\"protected\":\"yes\",\"recoveries\":2}";

    private static final String CHECKPOINTS = "{\"checkpoints\":["
        + "{\"label\":\"abc123\",\"createdAt\":\"2026-09-12 09:00\",\"status\":\"verified\"},"
        + "{\"label\":\"def456\",\"createdAt\":\"2026-09-12 10:30\",\"status\":\"verified\"},"
        + "{\"label\":\"ghi789\",\"createdAt\":\"\",\"status\":\"preserved\"}]}";

    @Test
    public void parsesStateFields() {
        DashboardModel m = DashboardModel.parse(STATE, CHECKPOINTS);
        assertEquals("protected", m.status());
        assertEquals("2026-09-12 14:02", m.lastVerified());
        assertEquals("3", m.checkpoints());
        assertEquals("9", m.snapshots());
        assertEquals("yes", m.protectedCount());
        assertEquals("2", m.recoveries());
    }

    @Test
    public void buildsTimelineRows() {
        DashboardModel m = DashboardModel.parse(STATE, CHECKPOINTS);
        assertEquals(3, m.rows().size());
        assertEquals("abc123", m.rows().get(0).label());
        assertEquals("verified", m.rows().get(0).status());
        assertEquals("2026-09-12 09:00", m.rows().get(0).time());
    }

    @Test
    public void blankInputYieldsEmptyModel() {
        assertEquals(DashboardModel.EMPTY, DashboardModel.parse("", ""));
        assertEquals(DashboardModel.EMPTY, DashboardModel.parse(null, null));
    }

    @Test
    public void malformedJsonYieldsEmptyModel() {
        assertEquals(DashboardModel.EMPTY, DashboardModel.parse("{oops", "{also:bad"));
    }

    @Test
    public void missingCheckpointsArrayIsTolerated() {
        DashboardModel m = DashboardModel.parse(STATE, "{}");
        assertTrue(m.rows().isEmpty());
    }
}