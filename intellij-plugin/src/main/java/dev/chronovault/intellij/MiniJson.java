package dev.chronovault.intellij;

/**
 * Minimal, pure-JDK JSON parser / reader for the small subset consumed by the
 * IntelliJ plugin (flat objects, nested objects, arrays of objects/strings/primitives).
 * Not a general-purpose library; sufficient to parse ChronoVault dashboard JSON
 * without pulling Gson or Jackson onto the compile classpath.
 */
public final class MiniJson {
    private MiniJson() {}

    // ---- public read API ---------------------------------------------------

    /** Parse a JSON string into a tree: Map (object), List (array), String, Number, Boolean, null. */
    public static Object parse(String json) {
        return new Parser(json.trim()).value();
    }

    /** Convenience: parse then return a specific nested key as a raw string. */
    public static String getString(Object root, String key, String fallback) {
        if (root instanceof java.util.Map<?, ?> m) {
            Object v = m.get(key);
            return v == null ? fallback : String.valueOf(v);
        }
        return fallback;
    }

    public static Object getField(Object root, String key) {
        return root instanceof java.util.Map<?, ?> m ? m.get(key) : null;
    }

    public static java.util.List<?> getList(Object root, String key) {
        Object v = getField(root, key);
        return v instanceof java.util.List<?> l ? l : java.util.List.of();
    }

    public static java.util.List<java.util.Map<String, Object>> getObjectList(Object root, String key) {
        java.util.List<?> raw = getList(root, key);
        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
        for (Object item : raw) {
            if (item instanceof java.util.Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> cast = (java.util.Map<String, Object>) (java.util.Map<?, ?>) m;
                out.add(cast);
            }
        }
        return out;
    }

    /** Pretty-print a value back to compact JSON (useful in tests and diagnostics). */
    public static String stringify(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    // ---- tiny recursive-descent parser ------------------------------------

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) { this.s = s; }

        Object value() { return readValue(); }

        private Object readValue() {
            skipWs();
            if (i >= s.length()) throw error("unexpected end of input");
            char c = s.charAt(i);
            if (c == '"') return readString();
            if (c == '{') return readObject();
            if (c == '[') return readArray();
            if (c == 't' || c == 'f') return readBoolean();
            if (c == 'n') return readNull();
            return readNumber();
        }

        private java.util.Map<String, Object> readObject() {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            i++; // '{'
            skipWs();
            if (peek() == '}') { i++; return m; }
            while (true) {
                skipWs();
                String key = readString();
                skipWs();
                expect(':');
                m.put(key, readValue());
                skipWs();
                char c = peek();
                if (c == '}') { i++; return m; }
                expect(',');
            }
        }

        private java.util.List<Object> readArray() {
            java.util.List<Object> a = new java.util.ArrayList<>();
            i++; // '['
            skipWs();
            if (peek() == ']') { i++; return a; }
            while (true) {
                a.add(readValue());
                skipWs();
                char c = peek();
                if (c == ']') { i++; return a; }
                expect(',');
            }
        }

        private String readString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char esc = i < s.length() ? s.charAt(i++) : 0;
                    switch (esc) {
                        case '"', '\\', '/' -> sb.append(esc);
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            String hex = s.substring(i, i + 4);
                            i += 4;
                            sb.appendCodePoint(Integer.parseInt(hex, 16));
                        }
                        default -> sb.append(esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw error("unterminated string");
        }

        private Number readNumber() {
            int start = i;
            if (peek() == '-') i++;
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            boolean isFloat = false;
            if (i < s.length() && s.charAt(i) == '.') { isFloat = true; i++; while (i < s.length() && Character.isDigit(s.charAt(i))) i++; }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) { isFloat = true; i++; if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++; while (i < s.length() && Character.isDigit(s.charAt(i))) i++; }
            String num = s.substring(start, i);
            if (isFloat) return Double.parseDouble(num);
            long v = Long.parseLong(num);
            if (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) return (int) v;
            return v;
        }

        private Boolean readBoolean() {
            if (s.startsWith("true", i)) { i += 4; return true; }
            if (s.startsWith("false", i)) { i += 5; return false; }
            throw error("expected boolean");
        }

        private Object readNull() {
            if (s.startsWith("null", i)) { i += 4; return null; }
            throw error("expected null");
        }

        private void skipWs() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        private char peek() { return i < s.length() ? s.charAt(i) : 0; }
        private void expect(char c) { skipWs(); if (i >= s.length() || s.charAt(i) != c) throw error("expected '" + c + "'"); i++; }
        private IllegalArgumentException error(String msg) { return new IllegalArgumentException("MiniJson @ " + i + ": " + msg); }
    }

    // ---- tiny writer -------------------------------------------------------

    private static void write(StringBuilder sb, Object v) {
        if (v == null) { sb.append("null"); }
        else if (v instanceof String s) { sb.append('"').append(escapeJson(s)).append('"'); }
        else if (v instanceof Boolean || v instanceof Number) { sb.append(v); }
        else if (v instanceof java.util.Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (var e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(escapeJson(String.valueOf(e.getKey()))).append('"').append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof java.util.List<?> a) {
            sb.append('[');
            for (int i = 0; i < a.size(); i++) { if (i > 0) sb.append(','); write(sb, a.get(i)); }
            sb.append(']');
        } else {
            sb.append('"').append(escapeJson(String.valueOf(v))).append('"');
        }
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}