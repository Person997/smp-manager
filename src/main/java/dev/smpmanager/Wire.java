package dev.smpmanager;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

public final class Wire {
    private Wire() {}
    public record Link(UUID uuid,String minecraftName,String discordId,long created,String method) {}
    public record Audit(long id,String actor,String action,String minecraftId,String discordId,long time,String detail) {}
    public record Event(long id,long time,String kind,String message,String discordId) {}
    public static String text(JsonNode node,String key) {
        JsonNode value=node.get(key);
        if(value==null || !value.isTextual() || value.asText().isBlank() || value.asText().length()>512) throw new IllegalArgumentException("Missing or invalid "+key);
        return value.asText();
    }
    public static String snowflake(String id) {
        if(id==null || !id.matches("[1-9][0-9]{16,19}")) throw new IllegalArgumentException("Invalid Discord ID");
        Long.parseUnsignedLong(id); return id;
    }
}
