package dev.smpmanager;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;

public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private Json() { }
    public static byte[] bytes(Object value) {
        try { return MAPPER.writeValueAsBytes(value); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("Cannot encode JSON", e); }
    }
    public static String string(Object value) { return new String(bytes(value), java.nio.charset.StandardCharsets.UTF_8); }
    public static <T> T read(byte[] bytes, Class<T> type) {
        try { return MAPPER.readValue(bytes, type); }
        catch (java.io.IOException e) { throw new IllegalArgumentException("Invalid JSON", e); }
    }
    public static JsonNode tree(Object value) { return MAPPER.valueToTree(value); }
}
