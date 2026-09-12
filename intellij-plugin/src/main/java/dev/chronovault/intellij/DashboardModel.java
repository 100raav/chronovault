package dev.chronovault.intellij;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pure display model for the native dashboard panel — transforms dashboard API
 * JSON (parsed with {@link MiniJson}) into Swing-friendly text rows, so the
 * panel itself stays free of any JSON or networking logic. Cannot touch an IDE.
 */
public record DashboardModel(String status, String lastVerified, String checkpoints,
                             String snapshots, String protectedCount, String recoveries,
                             List<DashboardModel.Row> rows) {

    /** One row in the checkpoint timeline. */
    public record Row(String label, String time, String status) {}

    public static DashboardModel EMPTY = new DashboardModel("unknown", "never", "0", "0", "no", "0", List.of());

    /** Build a model from the raw JSON strings of the state/checkpoints endpoints. */
    public static DashboardModel parse(String stateJson, String checkpointsJson) {
        if (stateJson == null || stateJson.isBlank()) return EMPTY;
        Map<String, Object> state;
        Map<String, Object> cps;
        try {
            state = toMap(MiniJson.parse(stateJson));
            cps = checkpointsJson == null || checkpointsJson.isBlank() ? null : toMap(MiniJson.parse(checkpointsJson));
        } catch (RuntimeException re) {
            return EMPTY;
        }

        List<Row> rows = new ArrayList<>();
        if (cps != null) {
            for (java.util.Map<String, Object> c : MiniJson.getObjectList(cps, "checkpoints")) {
                String label = str(c, "label", str(c, "name", "checkpoint"));
                String time = str(c, "createdAt", str(c, "time", ""));
                String status = str(c, "status", str(c, "verified", "")).equals("true") ? "verified" : str(c, "status", "");
                rows.add(new Row(label, time, status));
            }
        }

        return new DashboardModel(
            str(state, "status", EMPTY.status()),
            str(state, "lastVerified", EMPTY.lastVerified()),
            str(state, "checkpoints", EMPTY.checkpoints()),
            str(state, "snapshots", EMPTY.snapshots()),
            str(state, "protected", EMPTY.protectedCount()),
            str(state, "recoveries", EMPTY.recoveries()),
            List.copyOf(rows));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(Object o) {
        if (!(o instanceof Map)) throw new IllegalArgumentException("expected object");
        return (Map<String, Object>) (Map<?, ?>) o;
    }

    private static String str(java.util.Map<String, Object> m, String key, String fallback) {
        Object v = m.get(key);
        return v == null ? fallback : String.valueOf(v);
    }
}