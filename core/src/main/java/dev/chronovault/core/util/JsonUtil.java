package dev.chronovault.core.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import dev.chronovault.core.domain.*;

import java.lang.reflect.Type;
import java.time.Instant;
import java.util.List;

public final class JsonUtil {
    private JsonUtil() {}

    private static final TypeAdapter<Instant> INSTANT_ADAPTER = new TypeAdapter<>() {
        @Override
        public void write(JsonWriter out, Instant value) throws java.io.IOException {
            out.value(value == null ? null : value.toString());
        }

        @Override
        public Instant read(JsonReader in) throws java.io.IOException {
            String s = in.nextString();
            return s == null || s.isBlank() ? null : Instant.parse(s);
        }
    };

    public static final Gson GSON = new GsonBuilder()
        .serializeNulls()
        .disableHtmlEscaping()
        .setPrettyPrinting()
        .registerTypeAdapter(Instant.class, INSTANT_ADAPTER)
        .create();

    public static String toJson(Object o) {
        return GSON.toJson(o);
    }

    public static <T> T fromJson(String json, Type type) {
        return GSON.fromJson(json, type);
    }

    public static <T> T fromJson(String json, Class<T> clazz) {
        return GSON.fromJson(json, clazz);
    }

    public static String instantToIso(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}