package dev.smpmanager;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class ManagerConfig {
    private final Properties values=new Properties();
    public final int startPlayers,minimumPlayers,graceSeconds,joinSeconds,autopassSeconds,reservationMinutes,renewSeconds,afkSeconds,statusSeconds;
    public final boolean discordEnabled,afkEnabled;
    public final String token,guild,statusChannel,notificationChannel;
    public final Set<String> adminRoles;
    public final Set<UUID> autopassAdmins;
    public ManagerConfig(Path file) throws IOException {
        try(Reader reader=Files.newBufferedReader(file,StandardCharsets.UTF_8)) { values.load(reader); }
        startPlayers=bounded("players-required-to-open",5,1,200);
        minimumPlayers=bounded("minimum-online-players",4,1,startPlayers);
        graceSeconds=bounded("grace-period-seconds",300,1,604800);
        joinSeconds=bounded("startup-join-window-seconds",180,1,86400);
        autopassSeconds=bounded("autopass-grace-seconds",3600,1,604800);
        reservationMinutes=bounded("queue.reservation-minutes",60,1,1440);
        renewSeconds=bounded("queue.renew-cooldown-seconds",300,1,86400);
        afkEnabled=bool("afk.enabled",false); afkSeconds=bounded("afk.timeout-seconds",300,60,86400);
        statusSeconds=bounded("discord.status-update-seconds",10,5,3600);
        discordEnabled=bool("discord.enabled",false); token=text("discord.token",""); guild=text("discord.guild-id","");
        statusChannel=text("discord.status-channel-id",""); notificationChannel=text("discord.notification-channel-id",statusChannel);
        adminRoles=new HashSet<>(strings("discord.admin-role-ids"));
        autopassAdmins=new HashSet<>();
        try { for(String id:strings("admins.autopass-uuids")) autopassAdmins.add(UUID.fromString(id)); }
        catch(IllegalArgumentException invalid) { throw new IllegalArgumentException("admins.autopass-uuids must contain valid UUIDs"); }
        if(discordEnabled) {
            if(token.isBlank() || token.startsWith("PASTE_")) throw new IllegalArgumentException("Configure discord.token before enabling Discord");
            Wire.snowflake(guild); Wire.snowflake(statusChannel); Wire.snowflake(notificationChannel);
            adminRoles.forEach(Wire::snowflake);
        }
        if(number("verification.request_cooldown_seconds",30)<30) throw new IllegalArgumentException("Verification cooldown must be at least 30 seconds");
    }
    public String text(String key,String fallback) { return values.getProperty(key,fallback).trim(); }
    public int number(String key,int fallback) {
        try { return Integer.parseInt(text(key,""+fallback)); }
        catch(NumberFormatException invalid) { throw new IllegalArgumentException(key+" must be an integer"); }
    }
    public boolean bool(String key,boolean fallback) {
        String value=text(key,""+fallback);
        if(!Set.of("true","false").contains(value)) throw new IllegalArgumentException(key+" must be true or false");
        return Boolean.parseBoolean(value);
    }
    private int bounded(String key,int fallback,int min,int max) {
        int value=number(key,fallback);
        if(value<min || value>max) throw new IllegalArgumentException(key+" must be between "+min+" and "+max);
        return value;
    }
    private List<String> strings(String key) { return Arrays.stream(text(key,"").split(",")).map(String::trim).filter(s->!s.isEmpty()).toList(); }
}
