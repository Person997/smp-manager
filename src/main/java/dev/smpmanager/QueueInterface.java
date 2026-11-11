package dev.smpmanager;

import com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.event.connection.configuration.*;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import io.papermc.paper.registry.data.dialog.*;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import java.net.SocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

final class QueueInterface implements Listener,AutoCloseable {
    private static final NamespacedKey DISCONNECT_ACK=new NamespacedKey("smpmanager","disconnect_ack");
    private static final Key COMMANDS=Key.key("smpmanager:commands"),RUN=Key.key("smpmanager:run"),BACK=Key.key("smpmanager:back"),EXIT=Key.key("smpmanager:disconnect");
    private static final class Waiting {
        final PlayerConfigurationConnection connection;
        final boolean reconfiguration;
        final CompletableFuture<Void> released=new CompletableFuture<>();
        final QueueMenuState menu=new QueueMenuState();
        final AtomicBoolean ending=new AtomicBoolean();
        String displayed="";
        Waiting(PlayerConfigurationConnection connection,boolean reconfiguration) { this.connection=connection; this.reconfiguration=reconfiguration; }
    }
    private final SmpManagerPlugin plugin;
    private final QueueConnections<Waiting> waiting=new QueueConnections<>();
    private final ConcurrentMap<UUID,SocketAddress> moving=new ConcurrentHashMap<>();
    private final Set<Waiting> disconnecting=ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    QueueInterface(SmpManagerPlugin plugin) { this.plugin=plugin; }

    @EventHandler public void initial(AsyncPlayerConnectionConfigureEvent event) {
        PlayerConfigurationConnection connection=event.getConnection();
        if(plugin.engine().isOpen()) return;
        Waiting entry=register(connection,false,true);
        if(entry==null) return;
        try { entry.released.join(); }
        finally { abandon(entry); }
    }
    @EventHandler public void reconfigure(PlayerConnectionReconfigureEvent event) {
        register(event.getConnection(),true,false);
        if(plugin.engine().isOpen()) releaseAll();
    }
    private Waiting register(PlayerConfigurationConnection connection,boolean reconfiguration,boolean newIntent) {
        UUID id=connection.getProfile().getId();
        if(closed || id==null) { connection.disconnect(Component.text("SMP Manager is restarting. Please reconnect.")); return null; }
        Waiting existing=waiting.get(id);
        if(existing!=null && existing.connection.equals(connection)) return existing;
        Waiting entry=new Waiting(connection,reconfiguration);
        try {
            Waiting old=waiting.replace(id,entry,()->plugin.engine().waiting(id,connection.getProfile().getName(),newIntent));
            if(old!=null) disconnect(old,"Replaced by a new connection.");
            moving.remove(id);
            showQueue(entry,true);
            return entry;
        } catch(RuntimeException failure) { disconnect(entry,"Unable to enter the waiting queue. Check whitelist and server status."); return null; }
    }
    boolean contains(UUID id) { return waiting.contains(id) || moving.containsKey(id); }
    void returnToQueue(Player player) {
        if(moving.putIfAbsent(player.getUniqueId(),player.getConnection().getAddress())!=null) return;
        try { player.getConnection().reenterConfiguration(); }
        catch(RuntimeException failure) { moving.remove(player.getUniqueId()); player.kick(Component.text("World frozen. Reconnect to enter the waiting queue.")); }
    }
    void refresh() {
        for(Waiting entry:waiting.snapshot()) {
            try {
                if(!entry.connection.isConnected()) { abandon(entry); continue; }
                if(!plugin.queuedAccountAllowed(entry.connection.getProfile().getId())) { disconnect(entry,"Your account is no longer whitelisted."); continue; }
                if(plugin.engine().isOpen()) release(entry);
                else if(!entry.menu.commands()) showQueue(entry,false);
            } catch(RuntimeException failure) { disconnect(entry,"Unable to refresh the queue. Please reconnect."); }
        }
    }
    void releaseAll() { for(Waiting entry:waiting.snapshot()) release(entry); }
    private void release(Waiting entry) {
        synchronized(entry) {
            if(!plugin.queuedAccountAllowed(entry.connection.getProfile().getId())) { disconnect(entry,"Your account is no longer whitelisted."); return; }
            if(!entry.menu.finish()) return;
            try {
                entry.connection.getAudience().closeDialog();
                if(entry.reconfiguration) entry.connection.completeReconfiguration();
            } catch(RuntimeException failure) {
                try { entry.connection.disconnect(Component.text("Unable to enter the world. Please reconnect.")); } catch(RuntimeException ignored) { }
                plugin.getLogger().warning("A queue connection could not enter the world; other players are unaffected.");
            } finally { removePresence(entry); }
        }
    }
    private static ActionButton button(String text,Key key) { return ActionButton.builder(Component.text(text)).action(DialogAction.customClick(key,null)).build(); }
    private void showQueue(Waiting entry,boolean force) {
        synchronized(entry) {
            if(entry.menu.finished() || entry.menu.commands()) return;
            ManagerSnapshot status=plugin.engine().snapshot();
            String detail=status.state()==ManagerEngine.State.ERROR?status.detail():status.rearmRequired()?"World closed. An admin can /smp reset or /smp autopass; a new connection also re-arms the queue.":"The world opens automatically when enough unique players are queued.";
            String body="Waiting players: "+status.queueSize()+" / "+status.startPlayers()+"\n\n"+detail;
            if(!force && body.equals(entry.displayed)) return;
            entry.connection.getAudience().showDialog(Dialog.create(builder->builder.empty()
                    .base(DialogBase.builder(Component.text("SMP Manager",NamedTextColor.AQUA)).canCloseWithEscape(false).pause(false)
                            .afterAction(DialogBase.DialogAfterAction.NONE)
                            .body(List.of(DialogBody.plainMessage(Component.text("World closed",NamedTextColor.GOLD),340),
                                    DialogBody.plainMessage(Component.text(body),340))).build())
                    .type(DialogType.confirmation(button("Commands",COMMANDS),button("Disconnect",EXIT)))));
            entry.displayed=body;
        }
    }
    private void showCommands(Waiting entry,String result) {
        synchronized(entry) {
            if(entry.menu.finished()) return;
            entry.menu.showCommands();
            String body=result.isBlank()?"Enter /smp status or /smp link <code>. Authorized admins can use /smp autopass, /smp close and /smp reset.":result;
            if(body.length()>1800) body=body.substring(0,1800)+"…";
            String contents=body;
            entry.connection.getAudience().showDialog(Dialog.create(builder->builder.empty()
                    .base(DialogBase.builder(Component.text("SMP Manager — Commands",NamedTextColor.AQUA)).canCloseWithEscape(false).pause(false)
                            .afterAction(DialogBase.DialogAfterAction.NONE)
                            .body(List.of(DialogBody.plainMessage(Component.text(contents),360)))
                            .inputs(List.of(DialogInput.text("command",Component.text("Minecraft command")).width(340).build())).build())
                    .type(DialogType.multiAction(List.of(button("Run command",RUN),button("Back",BACK)),button("Disconnect",EXIT),2))));
        }
    }
    @EventHandler public void click(PlayerCustomClickEvent event) {
        if(!(event.getCommonConnection() instanceof PlayerConfigurationConnection connection)) return;
        Waiting entry=waiting.get(connection.getProfile().getId());
        if(entry==null || !entry.connection.equals(connection)) return;
        QueueMenuState.Request request;
        String[] arguments;
        synchronized(entry) {
            if(entry.menu.finished() || !waiting.isCurrent(connection.getProfile().getId(),entry)) return;
            if(event.getIdentifier().equals(EXIT)) { disconnect(entry,"Disconnected."); return; }
            if(event.getIdentifier().equals(COMMANDS)) { if(!entry.menu.commands()) showCommands(entry,""); return; }
            if(event.getIdentifier().equals(BACK)) { entry.menu.back(); showQueue(entry,true); return; }
            if(!event.getIdentifier().equals(RUN) || !entry.menu.commands()) return;
            String command=event.getDialogResponseView()==null?null:event.getDialogResponseView().getText("command");
            command=command==null?null:command.strip();
            if(command==null || command.length()>512 || !(command.equalsIgnoreCase("/smp") || command.toLowerCase(Locale.ROOT).startsWith("/smp "))) {
                showCommands(entry,"Commands must start with /smp. Example: /smp status"); return;
            }
            request=entry.menu.begin(); if(request==null) return;
            String[] args=command.split("\\s+");
            arguments=Arrays.copyOfRange(args,1,args.length);
        }
        plugin.work(()->{
            CommandContext context=plugin.main(()->{
                if(!entry.menu.running(request) || !waiting.isCurrent(connection.getProfile().getId(),entry) || !connection.isConnected()) throw new IllegalArgumentException("Queue connection ended; request canceled.");
                return plugin.queueContext(connection.getProfile().getId(),connection.getProfile().getName());
            });
            return plugin.commands().execute(context,arguments);
        },result->{
            if(plugin.isEnabledManager()) Bukkit.getScheduler().runTask(plugin,()->{
                synchronized(entry) {
                    if(entry.menu.complete(request) && waiting.isCurrent(connection.getProfile().getId(),entry) && !plugin.engine().isOpen()) showCommands(entry,result);
                }
            });
        });
    }
    @EventHandler public void disconnected(PlayerConnectionCloseEvent event) {
        Waiting entry=waiting.get(event.getPlayerUniqueId());
        if(entry!=null && entry.connection.getAddress().equals(event.getSocketAddress())) abandon(entry);
        for(Waiting closing:disconnecting) if(event.getPlayerUniqueId().equals(closing.connection.getProfile().getId()) && closing.connection.getAddress().equals(event.getSocketAddress())) abandon(closing);
        moving.remove(event.getPlayerUniqueId(),event.getSocketAddress());
    }
    private void abandon(Waiting entry) {
        synchronized(entry) {
            entry.menu.finish(); removePresence(entry);
        }
    }
    private void removePresence(Waiting entry) {
        unregisterPresence(entry);
        disconnecting.remove(entry);
        entry.released.complete(null);
    }
    private void unregisterPresence(Waiting entry) {
        UUID id=entry.connection.getProfile().getId();
        if(waiting.remove(id,entry,()->plugin.engine().disconnected(id))) moving.remove(id,entry.connection.getAddress());
    }
    private void disconnect(Waiting entry,String reason) {
        synchronized(entry) {
            if(!entry.menu.finish()) return;
            unregisterPresence(entry);
            disconnecting.add(entry);
            if(closed || !entry.connection.isConnected()) {
                try { entry.connection.getAudience().closeDialog(); } catch(RuntimeException ignored) { }
                endConnection(entry,reason); return;
            }
            // Both clear-dialog and cookie-request run on the client's packet-processing thread.
            // An empty response is sufficient; no cookie is stored or used as account data.
            DialogDisconnect.start(()->entry.connection.getAudience().closeDialog(),
                    ()->entry.connection.retrieveCookie(DISCONNECT_ACK),()->endConnection(entry,reason));
        }
    }
    private void endConnection(Waiting entry,String reason) {
        if(!entry.ending.compareAndSet(false,true)) return;
        try { entry.connection.disconnect(Component.text(reason)); }
        catch(RuntimeException ignored) { }
        finally {
            if(!entry.connection.isConnected()) removePresence(entry);
            else CompletableFuture.delayedExecutor(2,TimeUnit.SECONDS).execute(()->removePresence(entry));
        }
    }
    @Override public void close() {
        closed=true;
        for(Waiting entry:waiting.close()) disconnect(entry,"Minecraft is stopping. The world remains saved.");
        for(Waiting entry:disconnecting) endConnection(entry,"Minecraft is stopping. The world remains saved.");
        moving.clear();
    }
}
