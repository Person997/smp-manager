package dev.smpmanager;

import java.util.*;

public final class CommandAccess {
    public static final List<String> PUBLIC=List.of("status","link","help");
    public static final List<String> ADMIN=List.of("verify","confirm","unverify","list","pending","history","reset","close");
    private CommandAccess() {}
    public static boolean allowed(CommandContext context,String command) {
        return PUBLIC.contains(command) || command.equals("autopass") && (context.admin() || context.autopass()) || ADMIN.contains(command) && context.admin();
    }
    public static List<String> suggestions(CommandContext context) {
        List<String> result=new ArrayList<>(PUBLIC);
        if(context.admin() || context.autopass()) result.add("autopass");
        if(context.admin()) result.addAll(ADMIN);
        return result;
    }
}
