package dev.smpmanager;

import net.dv8tion.jda.api.*;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.*;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.*;
import net.dv8tion.jda.api.interactions.commands.build.*;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.requests.*;
import net.dv8tion.jda.api.utils.messages.MessageRequest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static dev.smpmanager.Store.*;

final class EmbeddedDiscord extends ListenerAdapter implements AutoCloseable {
    private static final String FOOTER="SMP Manager persistent status",CONFIRM="smpmanager-confirm:";
    private final SmpManagerPlugin plugin;
    private final ManagerConfig config;
    private final ScheduledExecutorService poller=Executors.newSingleThreadScheduledExecutor();
    private volatile JDA jda;
    private volatile boolean stopping;
    private Guild guild;
    private TextChannel statusChannel,alerts;
    private String messageId;
    private long edited,errorAt;
    EmbeddedDiscord(SmpManagerPlugin plugin) { this.plugin=plugin; config=plugin.settings(); }
    void start() throws Exception {
        MessageRequest.setDefaultMentions(Collections.emptySet());
        jda=JDABuilder.createLight(config.token).addEventListeners(this).build();
        jda.awaitReady();
        if(stopping) { jda.shutdownNow(); return; }
        guild=jda.getGuildById(config.guild);
        if(guild==null) throw new IllegalArgumentException("Invite the bot to the configured Discord server");
        statusChannel=guild.getTextChannelById(config.statusChannel); alerts=guild.getTextChannelById(config.notificationChannel);
        if(statusChannel==null || alerts==null) throw new IllegalArgumentException("Discord channel IDs must refer to text channels in the configured server");
        register();
        poller.scheduleWithFixedDelay(this::poll,0,5,TimeUnit.SECONDS);
    }
    private void register() throws Exception {
        List<CommandData> definitions=new ArrayList<>();
        definitions.add(Commands.slash("queue","Closed-world reservations").addSubcommands(
                new SubcommandData("join","Reserve a place in the closed-world queue"),new SubcommandData("renew","Explicitly renew a reservation"),
                new SubcommandData("leave","Remove your Discord reservation"),new SubcommandData("status","Show world or queue status")));
        definitions.add(Commands.slash("link","Generate a private code for /smp link in Minecraft"));
        definitions.add(Commands.slash("unlink","Revoke your Minecraft association and reservations"));
        definitions.add(Commands.slash("status","Show the world, queue and closing deadline"));
        definitions.add(Commands.slash("players","Show confirmed eligible world players"));
        definitions.add(Commands.slash("help","Show SMP Manager commands"));
        definitions.add(Commands.slash("close","Save and freeze the world; return players to its queue")
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER)));
        definitions.add(Commands.slash("admin","SMP Manager administration")
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
                .addSubcommands(new SubcommandData("verify","Prepare a manual account association")
                                .addOption(OptionType.STRING,"minecraft","Whitelisted Minecraft username",true).addOption(OptionType.USER,"discord","Human Discord member",true),
                        new SubcommandData("unverify","Revoke an account association").addOption(OptionType.USER,"discord","Discord account",true),
                        new SubcommandData("autopass","Open one session with the configured override grace"),
                        new SubcommandData("reset","Verify the save, clear a fault or re-arm a manually closed queue"))
                .addSubcommandGroups(new SubcommandGroupData("verification","Inspect account verification")
                        .addSubcommands(new SubcommandData("status","Inspect one account").addOption(OptionType.USER,"discord","Discord account",true),
                                new SubcommandData("list","List account links").addOption(OptionType.INTEGER,"page","Zero-based page",false),
                                new SubcommandData("pending","Inspect pending requests without revealing codes"),
                                new SubcommandData("history","Inspect the audit log").addOption(OptionType.USER,"discord","Filter account",false).addOption(OptionType.INTEGER,"page","Zero-based page",false))));
        await(guild.updateCommands().addCommands(definitions));
    }
    private boolean authorized(net.dv8tion.jda.api.entities.Member member) {
        return member!=null && (member.hasPermission(Permission.MANAGE_SERVER) || member.hasPermission(Permission.ADMINISTRATOR)) &&
                member.getRoles().stream().anyMatch(role->config.adminRoles.contains(role.getId()));
    }
    private CommandContext context(net.dv8tion.jda.api.entities.Member member) {
        if(!authorized(member)) throw new SecurityException("Requires a configured admin role plus Manage Server permission.");
        return new CommandContext(null,member.getEffectiveName(),"discord:"+member.getId(),true,true);
    }
    @Override public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if(stopping || guild==null || event.getGuild()==null || !event.getGuild().getId().equals(config.guild) || event.getUser().isBot()) {
            event.reply("SMP Manager is starting or this is not the configured server.").setEphemeral(true).queue(); return;
        }
        if(Set.of("admin","close").contains(event.getName()) && !authorized(event.getMember())) {
            event.reply("Requires a configured admin role ID and Manage Server permission.").setEphemeral(true).queue(); return;
        }
        event.deferReply(true).queue(hook->plugin.work(()->command(event,hook),message->{ if(message!=null && !message.equals("__handled__")) edit(hook,message); }));
    }
    private String command(SlashCommandInteractionEvent event,InteractionHook hook) throws Exception {
        String name=event.getName(),sub=Objects.requireNonNullElse(event.getSubcommandName(),""),id=event.getUser().getId();
        if(name.equals("close")) return plugin.commands().execute(context(event.getMember()),new String[]{"close"});
        if(name.equals("link")) {
            String code; synchronized(plugin.verification()) { code=plugin.verification().issue(id); }
            return "Your private single-use code is **`"+code+"`**. In Minecraft, use `/smp link "+code+"` in the world or the queue screen's Commands menu. Expires in five minutes.";
        }
        if(name.equals("unlink")) { synchronized(plugin.verification()) { plugin.verification().unlink(id,"discord:"+id); } return "Account link, reservations and outstanding codes revoked."; }
        if(name.equals("queue") && !sub.equals("status")) {
            if(sub.equals("leave")) return plugin.engine().leaveReservation(id);
            long expires=plugin.engine().reserve(id,sub.equals("renew"));
            return "Reservation active until <t:"+expires/1000+":F>. Repeated joins and status checks do not extend it.";
        }
        if(name.equals("admin")) {
            CommandContext admin=context(event.getMember());
            String discord=event.getOption("discord")==null?null:event.getOption("discord").getAsUser().getId();
            int page=event.getOption("page")==null?0:event.getOption("page").getAsInt();
            if(page<0) throw new IllegalArgumentException("Page must be zero or greater");
            if(sub.equals("verify")) {
                VerificationService.Candidate candidate=plugin.verification().resolve(Objects.requireNonNull(event.getOption("minecraft")).getAsString(),Objects.requireNonNull(discord));
                Map<String,Object> review;
                synchronized(plugin.verification()) { review=plugin.verification().prepare(admin.actor(),candidate); }
                String text="Review account ownership assertion:\nMinecraft: "+review.get("minecraft")+" ["+review.get("uuid")+"]\nDiscord: "+review.get("discordName")+"\nApprove only after checking both accounts. Expires in five minutes.";
                hook.editOriginal(text).setActionRow(Button.danger(CONFIRM+review.get("confirmation"),"Approve association")).queue(); return "__handled__";
            }
            if(sub.equals("status")) { synchronized(plugin.verification()) { return ManagerCommands.records(plugin.verification().links(discord,0)); } }
            if(sub.equals("list")) { synchronized(plugin.verification()) { return ManagerCommands.records(plugin.verification().links(null,page)); } }
            if(sub.equals("history")) { synchronized(plugin.verification()) { return ManagerCommands.records(plugin.verification().history(discord,page)); } }
            return plugin.commands().execute(admin,discord==null?new String[]{sub}:new String[]{sub,discord});
        }
        ManagerSnapshot snapshot=plugin.engine().snapshot();
        if(name.equals("players")) return snapshot.confirmed()?snapshot.players().isEmpty()?"No eligible world players.":String.join(", ",snapshot.players()):"World player count is currently unconfirmed.";
        if(name.equals("help")) return "SMP Manager: /status, /players, /link, /unlink, /queue join|renew|leave|status. Minecraft: /smp status and /smp link <code>. Authorized admins: /close, /admin autopass|reset|verify|unverify and /admin verification. The Minecraft process stays running; closed worlds freeze.";
        return statusText(snapshot);
    }
    @Override public void onButtonInteraction(ButtonInteractionEvent event) {
        if(!event.getComponentId().startsWith(CONFIRM)) return;
        if(event.getGuild()==null || !event.getGuild().getId().equals(config.guild) || !authorized(event.getMember())) {
            event.reply("Administrator authorization required.").setEphemeral(true).queue(); return;
        }
        event.deferReply(true).queue(hook->plugin.work(()->{
            CommandContext admin=context(event.getMember());
            synchronized(plugin.verification()) { plugin.verification().confirm(admin.actor(),UUID.fromString(event.getComponentId().substring(CONFIRM.length()))); }
            event.getMessage().editMessageComponents().queue(); return "Account association approved and audited.";
        },message->edit(hook,message)));
    }
    private void poll() {
        if(stopping) return;
        try {
            if(System.currentTimeMillis()-edited>=config.statusSeconds*1000L) {
                ManagerSnapshot snapshot=plugin.engine().snapshot();
                updateStatus(new EmbedBuilder().setTitle("SMP Manager").setDescription(statusText(snapshot)).setColor(snapshot.worldOpen()?0x46B880:snapshot.state()==ManagerEngine.State.ERROR?0xD94C4C:0xD9AA4C)
                        .setFooter(FOOTER).setTimestamp(Instant.now()).build());
                edited=System.currentTimeMillis();
            }
            long cursor=Long.parseLong(plugin.store().metadata("manager.discord.cursor","0"));
            for(Wire.Event event:plugin.store().events(cursor)) {
                if(event.discordId()!=null) {
                    try { User user=await(jda.retrieveUserById(event.discordId())); await(await(user.openPrivateChannel()).sendMessage(event.message())); }
                    catch(net.dv8tion.jda.api.exceptions.ErrorResponseException failure) {
                        if(!Set.of(ErrorResponse.CANNOT_SEND_TO_USER,ErrorResponse.UNKNOWN_USER,ErrorResponse.MISSING_ACCESS).contains(failure.getErrorResponse())) throw failure;
                    }
                } else if(!event.kind().equals("ACTIVE")) await(alerts.sendMessage("**SMP Manager — "+event.kind()+"**\n"+event.message()));
                plugin.store().setMetadata("manager.discord.cursor",""+event.id());
            }
        } catch(Exception failure) {
            if(System.currentTimeMillis()-errorAt>60_000) { plugin.getLogger().warning("Discord synchronization unavailable ("+failure.getClass().getSimpleName()+"). Minecraft continues independently."); errorAt=System.currentTimeMillis(); }
        }
    }
    private void updateStatus(MessageEmbed embed) throws Exception {
        if(messageId==null) {
            messageId=plugin.store().metadata("discord.status_message","");
            if(messageId.isBlank()) {
                for(Message message:await(statusChannel.getHistory().retrievePast(100))) {
                    if(message.getAuthor().getId().equals(jda.getSelfUser().getId()) && message.getEmbeds().stream().anyMatch(e->e.getFooter()!=null && Set.of(FOOTER,"SMP Quorum persistent status").contains(e.getFooter().getText()))) { messageId=message.getId(); break; }
                }
                if(messageId.isBlank()) messageId=await(statusChannel.sendMessageEmbeds(embed)).getId();
                plugin.store().setMetadata("discord.status_message",messageId);
            }
        }
        try { await(statusChannel.editMessageEmbedsById(messageId,embed)); }
        catch(net.dv8tion.jda.api.exceptions.ErrorResponseException failure) {
            if(failure.getErrorResponse()!=ErrorResponse.UNKNOWN_MESSAGE) throw failure;
            messageId=await(statusChannel.sendMessageEmbeds(embed)).getId(); plugin.store().setMetadata("discord.status_message",messageId);
        }
    }
    private static String statusText(ManagerSnapshot status) {
        String population=status.worldOpen()?"Eligible world players: **"+(status.confirmed()?status.online():"unconfirmed")+"**\nMinimum to stay open: **"+status.minimumPlayers()+"**":
                "World simulation: **frozen**\nQueue: **"+status.queueSize()+" / "+status.startPlayers()+" unique players**";
        String deadline=status.deadline()>0?"\n"+(status.state()==ManagerEngine.State.JOINING?"Join window ends":"World closes")+" <t:"+status.deadline()/1000+":R>":"";
        return "State: **"+status.state()+"**\n"+population+(status.autopass()?"\n**Admin autopass: this session only**":"")+deadline+"\n"+status.detail()+
                (status.rearmRequired()?"\nReconnect, explicitly reserve in Discord, or ask an admin to re-arm the queue.":"")+"\nLast world sample: <t:"+status.updated()/1000+":T>";
    }
    private static void edit(InteractionHook hook,String message) {
        List<String> parts=new ArrayList<>();
        while(message.length()>1900) { int cut=message.lastIndexOf('\n',1900); if(cut<500) cut=1900; parts.add(message.substring(0,cut)); message=message.substring(cut).stripLeading(); }
        parts.add(message.isBlank()?"OK":message);
        hook.editOriginal(parts.getFirst()).queue(ignored->{ for(int n=1;n<parts.size();n++) hook.sendMessage(parts.get(n)).setEphemeral(true).queue(); });
    }
    private static <T> T await(RestAction<T> request) throws Exception { return request.submit().get(15,TimeUnit.SECONDS); }
    @Override public void close() {
        stopping=true; poller.shutdownNow();
        if(jda!=null) jda.shutdownNow();
        try { poller.awaitTermination(2,TimeUnit.SECONDS); } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
