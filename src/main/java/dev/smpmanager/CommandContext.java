package dev.smpmanager;

import java.util.UUID;

public record CommandContext(UUID uuid,String name,String actor,boolean admin,boolean autopass) {}
