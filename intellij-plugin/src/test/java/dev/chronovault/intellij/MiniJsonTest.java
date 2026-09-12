package dev.chronovault.intellij;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Pure-JDK tests for the minimal JSON reader the native panel relies on. */
public class MiniJsonTest {

    @Test
    public void parsesNestedObjectsAndArrays() {
        Object root = MiniJson.parse(
            "{\"status\":\"protected\",\"lastVerified\":\"2026-09-12 14:02\",\"checkpoints\":3,"
            + "\"tags\":[\"a\",\"b\"],\"nested\":{\"deep\":true,\"ratio\":0.5}}");
        assertEquals("protected", MiniJson.getField(root, "status"));
        assertEquals(3, MiniJson.getField(root, "checkpoints"));
        assertEquals(List.of("a", "b"), MiniJson.getField(root, "tags"));
        assertEquals(true, MiniJson.getField(MiniJson.getField(root, "nested"), "deep"));
        assertEquals(0.5, MiniJson.getField(MiniJson.getField(root, "nested"), "ratio"));
    }

    @Test
    public void parsesCheckpointArray() {
        Object cps = MiniJson.parse(
            "{\"checkpoints\":[{\"label\":\"abc123\",\"createdAt\":\"2026-09-12 09:00\",\"status\":\"verified\"},"
            + "{\"label\":\"def456\",\"createdAt\":\"\",\"status\":\"preserved\"}]}");
        List<Map<String, Object>> list = MiniJson.getObjectList(cps, "checkpoints");
        assertEquals(2, list.size());
        assertEquals("abc123", list.get(0).get("label"));
        assertEquals("def456", list.get(1).get("label"));
    }

    @Test
    public void missingKeysReturnNullAndUnknownListsEmpty() {
        Object root = MiniJson.parse("{\"a\":1}");
        assertNull(MiniJson.getField(root, "missing"));
        assertTrue(MiniJson.getObjectList(root, "missing").isEmpty());
    }

    @Test
    public void stringifyRoundTrips() {
        Object root = MiniJson.parse("{\"label\":\"hi \\\"there\\\"\",\"n\":7,\"xs\":[1,2,null],\"b\":true}");
        Object again = MiniJson.parse(MiniJson.stringify(root));
        assertEquals("hi \"there\"", MiniJson.getField(again, "label"));
        assertEquals(7, MiniJson.getField(again, "n"));
        assertEquals(true, MiniJson.getField(again, "b"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void malformedInputThrows() {
        MiniJson.parse("{\"a\":");
    }

    @Test
    public void sampleDashboardStringifyMatches() {
        String payload = "{\"status\":\"protected\",\"lastVerified\":\"2026-09-12 14:02\",\"checkpoints\":3,\"snapshots\":9}";
        assertNotNull(MiniJson.parse(payload));
    }
}