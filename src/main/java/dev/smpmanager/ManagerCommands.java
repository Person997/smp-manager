package dev.smpmanager;

import org.bukkit.command.*;
import java.util.*;

public final class ManagerCommands implements CommandExecutor,TabCompleter {
    private final SmpManagerPlugin plugin;
    ManagerCommands(SmpManagerPlugin plugin) { this.plugin=plugin; }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        CommandContext context=plugin.context(sender);
        if(args.length==0) { sender.sendMessage("Use /smp status or /smp help."); return true; }
        plugin.work(() -> execute(context,args),message -> plugin.reply(sender,message)); return true;
    }
    public String execute(CommandContext context,String[] args) throws Exception {
        if(args.length==0) return "Use /smp status or /smp help.";
        String verb=args[0].toLowerCase(Locale.ROOT);
        if(!CommandAccess.allowed(context,verb)) {
            if(!CommandAccess.PUBLIC.contains(verb) && !CommandAccess.ADMIN.contains(verb) && !verb.equals("autopass")) return "Unknown command. Use /smp help.";
            throw new SecurityException("Administrator permission required.");
        }
        switch(verb) {
            case "status": require(args,1,"/smp status"); return plugin.engine().snapshot().text(System.currentTimeMillis());
            case "help": return help(context);
            case "link": {
                require(args,2,"/smp link <six-digit code>");
                if(context.uuid()==null) throw new IllegalArgumentException("Use this command from your authenticated Minecraft account.");
                synchronized(plugin.verification()) { plugin.verification().verify(context.uuid(),context.name(),args[1]); }
                return "Your Minecraft and Discord accounts are linked.";
            }
            case "autopass": require(args,1,"/smp autopass"); return plugin.main(() -> { plugin.validateEnvironment(); String result=plugin.engine().autopass(context.actor()); plugin.applyWorldState(); return result; });
            case "close": require(args,1,"/smp close"); return plugin.main(() -> {
                plugin.engine().close(context.actor(),"World closed by an administrator; waiting queue active."); plugin.applyWorldState();
                if(plugin.engine().snapshot().state()==ManagerEngine.State.ERROR) throw new IllegalArgumentException("World frozen, but its save failed. Repair storage and use /smp reset.");
                return "World saved and frozen. Players returned to the queue. Use /smp reset to re-arm normal opening, or /smp autopass to open an override session.";
            });
            case "reset": require(args,1,"/smp reset"); return plugin.main(() -> { plugin.validateEnvironment(); plugin.saveWorld(); String result=plugin.engine().reset(context.actor()); plugin.applyWorldState(); return result; });
            case "verify": {
                require(args,3,"/smp verify <Minecraft username> <Discord user ID>");
                VerificationService.Candidate candidate=plugin.verification().resolve(args[1],args[2]);
                Map<String,Object> review;
                synchronized(plugin.verification()) { review=plugin.verification().prepare(context.actor(),candidate); }
                return "Review: Minecraft "+review.get("minecraft")+" ["+review.get("uuid")+"] ↔ Discord "+review.get("discordName")+". This is your assertion of account ownership. Approve within five minutes: /smp confirm "+review.get("confirmation");
            }
            case "confirm": {
                require(args,2,"/smp confirm <confirmation token>");
                synchronized(plugin.verification()) { plugin.verification().confirm(context.actor(),UUID.fromString(args[1])); }
                return "Account association approved and audited.";
            }
            case "unverify": {
                require(args,2,"/smp unverify <Discord user ID>");
                synchronized(plugin.verification()) { plugin.verification().unlink(args[1],context.actor()); }
                return "Verification revoked; reservations and outstanding codes removed.";
            }
            case "list": {
                if(args.length>3) throw new IllegalArgumentException("Usage: /smp list [Discord user ID] [page]");
                String discord=args.length>1 && args[1].matches("[0-9]{16,19}")?args[1]:null;
                int page=args.length>2?Integer.parseInt(args[2]):args.length>1 && discord==null?Integer.parseInt(args[1]):0;
                if(page<0) throw new IllegalArgumentException("Page must be zero or greater");
                synchronized(plugin.verification()) { return records(plugin.verification().links(discord,page)); }
            }
            case "pending": require(args,1,"/smp pending"); synchronized(plugin.verification()) { return Json.string(plugin.verification().pending()); }
            case "history": {
                if(args.length>3) throw new IllegalArgumentException("Usage: /smp history [Discord user ID] [page]");
                String discord=args.length>1?args[1]:null; int page=args.length>2?Integer.parseInt(args[2]):0;
                if(page<0) throw new IllegalArgumentException("Page must be zero or greater");
                synchronized(plugin.verification()) { return records(plugin.verification().history(discord,page)); }
            }
            default: return "Unknown command. Use /smp help.";
        }
    }
    static String records(List<?> records) {
        if(records.isEmpty()) return "No matching records.";
        StringBuilder text=new StringBuilder();
        for(Object record:records) {
            if(record instanceof Wire.Link link) text.append(link.minecraftName()).append(" [").append(link.uuid()).append("] ↔ ").append(link.discordId()).append(" (").append(link.method()).append(")\n");
            else if(record instanceof Wire.Audit audit) text.append('#').append(audit.id()).append(' ').append(audit.action()).append(" by ").append(audit.actor()).append(" — ").append(audit.minecraftId()).append(" / ").append(audit.discordId()).append('\n');
        }
        return text.toString().strip();
    }
    private static void require(String[] args,int length,String usage) { if(args.length!=length) throw new IllegalArgumentException("Usage: "+usage); }
    static String help(CommandContext context) {
        String text="SMP Manager: /smp status | /smp link <code> | /smp help";
        if(context.admin() || context.autopass()) text+="\n/smp autopass — one-session override";
        if(context.admin()) text+="\n/smp close | /smp reset | /smp verify <name> <Discord ID> | /smp confirm <token> | /smp unverify <Discord ID> | /smp list [Discord ID] [page] | /smp pending | /smp history [Discord ID] [page]";
        return text;
    }
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args) {
        if(args.length!=1) return List.of();
        String prefix=args[0].toLowerCase(Locale.ROOT);
        return CommandAccess.suggestions(plugin.context(sender)).stream().filter(s->s.startsWith(prefix)).toList();
    }
}
