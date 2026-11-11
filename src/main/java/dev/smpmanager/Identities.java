package dev.smpmanager;


import com.fasterxml.jackson.databind.JsonNode;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

public interface Identities {
    record Minecraft(UUID uuid, String name) { }
    boolean whitelisted(UUID uuid);
    String name(UUID uuid);
    Minecraft resolveMinecraft(String username);
    String resolveDiscord(String id);

    final class Official implements Identities {
        private volatile Map<UUID, String> whitelist = new HashMap<>();
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        private final String guild;
        private final String token;
        public Official(Path whitelistFile, String guild, String token) throws java.io.IOException {
            JsonNode root = Json.MAPPER.readTree(Files.readAllBytes(whitelistFile));
            if (!root.isArray()) throw new IllegalArgumentException("whitelist.json must be a UUID/name array");
            for (JsonNode entry : root) {
                UUID uuid = UUID.fromString(Wire.text(entry,"uuid")); String name = Wire.text(entry,"name");
                if (!name.matches("[A-Za-z0-9_]{1,16}") || whitelist.put(uuid,name) != null)
                    throw new IllegalArgumentException("Invalid or duplicate whitelist entry");
            }
            this.guild = guild; this.token=token;
        }
        public void updateWhitelist(Map<UUID,String> current) { whitelist=Map.copyOf(current); }
        public boolean whitelisted(UUID uuid) { return whitelist.containsKey(uuid); }
        public String name(UUID uuid) { return whitelist.getOrDefault(uuid, uuid.toString()); }
        public Minecraft resolveMinecraft(String username) {
            if (!username.matches("[A-Za-z0-9_]{1,16}")) throw new IllegalArgumentException("Invalid Minecraft username");
            JsonNode profile = get("https://api.mojang.com/users/profiles/minecraft/" + username, null);
            String raw = Wire.text(profile,"id");
            if (!raw.matches("[a-fA-F0-9]{32}")) throw new IllegalArgumentException("Invalid official profile response");
            UUID uuid = UUID.fromString(raw.replaceFirst("(.{8})(.{4})(.{4})(.{4})(.{12})", "$1-$2-$3-$4-$5"));
            if (!whitelisted(uuid)) throw new IllegalArgumentException("Minecraft account is not whitelisted");
            return new Minecraft(uuid, Wire.text(profile,"name"));
        }
        public String resolveDiscord(String id) {
            Wire.snowflake(id); Wire.snowflake(guild);
            if (token == null || token.isBlank()) throw new IllegalArgumentException("Enable the Discord integration to validate manual Discord associations");
            JsonNode member = get("https://discord.com/api/v10/guilds/" + guild + "/members/" + id, token).path("user");
            if (!id.equals(member.path("id").asText()) || member.path("bot").asBoolean())
                throw new IllegalArgumentException("Discord account must be a human member of the configured guild");
            return member.path("username").asText() + " (" + id + ")";
        }
        private JsonNode get(String uri, String token) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(3))
                    .header("User-Agent", "SMPManager/1.1");
            if (token != null) builder.header("Authorization", "Bot " + token);
            try {
                var response = http.send(builder.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() != 200) throw new IllegalArgumentException("Identity lookup failed (HTTP " + response.statusCode() + "); try later");
                return Json.MAPPER.readTree(response.body());
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalArgumentException("Identity lookup interrupted"); }
            catch (java.io.IOException e) { throw new IllegalArgumentException("Official identity service unavailable", e); }
        }
    }
}
