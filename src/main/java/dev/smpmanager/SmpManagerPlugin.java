package dev.smpmanager;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.plugin.java.JavaPlugin;
import net.kyori.adventure.text.Component;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public final class SmpManagerPlugin extends JavaPlugin implements Listener {
    private ManagerConfig settings;
    private Store store;
    private ManagerEngine engine;
    private VerificationService verification;
    private Identities.Official identities;
    private ManagerCommands commands;
    private QueueInterface queue;
    private EmbeddedDiscord discord;
    private FileChannel lockChannel;
    private FileLock lock;
    private final ExecutorService workers=new ThreadPoolExecutor(2,4,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(64),new ThreadPoolExecutor.AbortPolicy());
    private final Map<UUID,Long> activity=new ConcurrentHashMap<>();
    private final Set<UUID> afkWarned=new HashSet<>();
    private final ShutdownWarnings warnings=new ShutdownWarnings();
    private volatile boolean enabled,loaded;
    private Boolean appliedOpen;
    private long cleanupAt;
    private ManagerEngine.State previousState;

    @Override public void onEnable() {
        // Deny joins even when settings/database initialization fails before the queue exists.
        Bukkit.getPluginManager().registerEvents(this,this);
        try {
            Path folder=getDataFolder().toPath(); Files.createDirectories(folder);
            Path config=folder.resolve("settings.properties");
            if(!Files.exists(config)) try(var source=Objects.requireNonNull(getResource("settings.properties"))) { Files.copy(source,config); }
            settings=new ManagerConfig(config);
            lockChannel=FileChannel.open(folder.resolve("data.db.plugin.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            lock=lockChannel.tryLock(); if(lock==null) throw new IllegalStateException("Another SMP Manager owns this database");
            store=new Store(folder.resolve("data.db"));
            // A migrated legacy outbox is historical. New events retain their own durable cursor.
            if(store.metadata("manager.discord.cursor","").isBlank()) store.setMetadata("manager.discord.cursor",
                    ""+Store.num(store.rows("SELECT COALESCE(MAX(id),0) AS n FROM events").getFirst(),"n"));
            Path keyFile=folder.resolve("verification.key");
            if(!Files.exists(keyFile)) Files.writeString(keyFile,UUID.randomUUID().toString()+UUID.randomUUID(),StandardOpenOption.CREATE_NEW);
            String key=Files.readString(keyFile).strip(); if(key.length()<32) throw new IllegalArgumentException("verification.key is invalid");
            identities=new Identities.Official(Path.of("whitelist.json"),settings.guild,settings.token);
            engine=new ManagerEngine(store,settings,identities,Clock.systemUTC());
            verification=new VerificationService(store,identities,settings,Clock.systemUTC(),key);
            commands=new ManagerCommands(this);
            Objects.requireNonNull(getCommand("smp")).setExecutor(commands); getCommand("smp").setTabCompleter(commands);
            queue=new QueueInterface(this);
            Bukkit.getPluginManager().registerEvents(queue,this);
            Bukkit.getServer().setPauseWhenEmptyTime(-1);
            Bukkit.getServerTickManager().setFrozen(true);
            enabled=true;
            try { validateEnvironment(); } catch(RuntimeException unsafe) { engine.fail(unsafe.getMessage()); }
            Bukkit.getScheduler().runTaskTimer(this,this::tick,1,20);
            Bukkit.getScheduler().runTaskTimer(this,this::warn,1,1);
            if(settings.discordEnabled) {
                discord=new EmbeddedDiscord(this);
                work(() -> { discord.start(); return "Discord integration connected."; },message -> getLogger().info(message));
            } else getLogger().info("Discord is disabled. Configure plugins/SMPManager/settings.properties to enable it.");
            getLogger().info("SMP Manager enabled: one Paper server, built-in controller/bot, closed worlds freeze.");
        } catch(Exception failure) {
            try { Bukkit.getServerTickManager().setFrozen(true); } catch(Exception ignored) { }
            String reason=failure instanceof IllegalArgumentException || failure instanceof IllegalStateException?failure.getMessage():failure.getClass().getSimpleName();
            getLogger().severe("SMP Manager could not start. World kept frozen. Check configuration/database: "+reason);
            Objects.requireNonNull(getCommand("smp")).setExecutor((sender,command,label,args)->{ sender.sendMessage("SMP Manager configuration failed. Fix settings.properties and restart the server."); return true; });
        }
    }
    public void validateEnvironment() {
        if(!Bukkit.getMinecraftVersion().startsWith("26.3")) throw new IllegalArgumentException("SMP Manager 1.1 targets Minecraft Java 26.3.");
        if(!Bukkit.getOnlineMode()) throw new IllegalArgumentException("Set online-mode=true. This single-server edition authenticates accounts directly.");
        if(!Bukkit.hasWhitelist()) throw new IllegalArgumentException("Enable the Minecraft whitelist and add the SMP accounts before opening the world.");
    }
    @EventHandler public void loaded(ServerLoadEvent event) { loaded=true; applyWorldState(); }
    private void tick() {
        if(!enabled || !loaded) return;
        try {
            Map<UUID,String> whitelist=new HashMap<>();
            for(OfflinePlayer player:Bukkit.getWhitelistedPlayers()) whitelist.put(player.getUniqueId(),Objects.requireNonNullElse(player.getName(),player.getUniqueId().toString()));
            identities.updateWhitelist(whitelist);
            long now=System.currentTimeMillis(); Map<UUID,String> active=new HashMap<>();
            for(Player player:Bukkit.getOnlinePlayers()) {
                UUID id=player.getUniqueId();
                long idle=now-activity.getOrDefault(id,now);
                long warningAfter=(settings.afkSeconds-Math.min(60,settings.afkSeconds/2))*1000L;
                if(settings.afkEnabled && idle>=warningAfter && afkWarned.add(id)) player.sendMessage(Component.text("AFK warning: move or interact to remain eligible for the player threshold."));
                if(idle<warningAfter) afkWarned.remove(id);
                if(player.getGameMode()!=GameMode.SPECTATOR && !queue.contains(id) && (!settings.afkEnabled || idle<settings.afkSeconds*1000L)) active.put(id,player.getName());
            }
            engine.sample(active); engine.tick(); applyWorldState(); queue.refresh();
            ManagerEngine.State state=engine.snapshot().state();
            if(previousState==ManagerEngine.State.COUNTDOWN && state==ManagerEngine.State.ACTIVE) Bukkit.broadcast(Component.text("Player threshold met. World closing canceled."));
            previousState=state;
            if(now-cleanupAt>=30_000) { synchronized(verification) { verification.cleanup(); } cleanupAt=now; }
        } catch(Exception failure) {
            getLogger().log(java.util.logging.Level.SEVERE,"SMP Manager world control failed",failure);
            failWorld("World control failed; inspect server logs ("+failure.getClass().getSimpleName()+").");
        }
    }
    private void warn() {
        if(!enabled || !loaded || !engine.isOpen()) return;
        warnings.update(engine.snapshot(),System.currentTimeMillis()).ifPresent(message->Bukkit.broadcast(Component.text(message)));
    }
    public void applyWorldState() {
        if(engine==null || queue==null || !loaded) return;
        boolean open=engine.isOpen();
        if(open) {
            validateEnvironment();
            Bukkit.getServerTickManager().setFrozen(false);
            queue.releaseAll();
        } else {
            Bukkit.getServerTickManager().setFrozen(true);
            if(appliedOpen==null || appliedOpen) {
                try { saveWorld(); }
                catch(Exception failure) { engine.fail("World save failed. Repair storage and use /smp reset; gameplay remains blocked."); }
                for(Player player:new ArrayList<>(Bukkit.getOnlinePlayers())) queue.returnToQueue(player);
            }
        }
        appliedOpen=open;
    }
    public void saveWorld() {
        if(store!=null) store.setMetadata("manager.pending_save","true");
        Bukkit.savePlayers(); for(World world:Bukkit.getWorlds()) world.save();
        if(store!=null) store.setMetadata("manager.pending_save","false");
    }
    private void failWorld(String reason) {
        Bukkit.getServerTickManager().setFrozen(true);
        if(engine!=null && engine.snapshot().state()!=ManagerEngine.State.ERROR) engine.fail(reason);
        if(queue!=null) for(Player player:new ArrayList<>(Bukkit.getOnlinePlayers())) queue.returnToQueue(player);
        appliedOpen=false;
        getLogger().severe(reason);
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void preLogin(AsyncPlayerPreLoginEvent event) {
        if(!enabled || identities==null || !identities.whitelisted(event.getUniqueId())) event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST,Component.text("This SMP requires a whitelisted authenticated account."));
    }
    @EventHandler public void joined(PlayerJoinEvent event) {
        Player player=event.getPlayer(); activity.put(player.getUniqueId(),System.currentTimeMillis());
        if(engine.isOpen()) engine.joined(player.getUniqueId());
        else Bukkit.getScheduler().runTask(this,()->{ if(player.isOnline()) queue.returnToQueue(player); });
    }
    @EventHandler public void quit(PlayerQuitEvent event) { activity.remove(event.getPlayer().getUniqueId()); afkWarned.remove(event.getPlayer().getUniqueId()); }
    private boolean blocked() { return !enabled || engine==null || !engine.isOpen(); }
    private void active(Player player) { activity.put(player.getUniqueId(),System.currentTimeMillis()); }
    @EventHandler(ignoreCancelled=true) public void move(PlayerMoveEvent event) {
        if(blocked()) { event.setCancelled(true); return; }
        Location to=event.getTo(),from=event.getFrom();
        if(to!=null && (!to.getWorld().equals(from.getWorld()) || to.distanceSquared(from)>.0025 || Math.abs(to.getYaw()-from.getYaw())>=5 || Math.abs(to.getPitch()-from.getPitch())>=5)) active(event.getPlayer());
    }
    @EventHandler(ignoreCancelled=true) public void breakBlock(BlockBreakEvent event) { if(blocked()) event.setCancelled(true); else active(event.getPlayer()); }
    @EventHandler(ignoreCancelled=true) public void place(BlockPlaceEvent event) { if(blocked()) event.setCancelled(true); else active(event.getPlayer()); }
    @EventHandler public void interact(PlayerInteractEvent event) { if(blocked()) event.setCancelled(true); else active(event.getPlayer()); }
    @EventHandler public void entityInteract(PlayerInteractEntityEvent event) { if(blocked()) event.setCancelled(true); else active(event.getPlayer()); }
    @EventHandler public void damage(EntityDamageEvent event) { if(blocked()) event.setCancelled(true); }
    @EventHandler public void hunger(FoodLevelChangeEvent event) { if(blocked()) event.setCancelled(true); }
    @EventHandler public void inventory(InventoryClickEvent event) { if(blocked()) event.setCancelled(true); else if(event.getWhoClicked() instanceof Player player) active(player); }
    @EventHandler public void drag(InventoryDragEvent event) { if(blocked()) event.setCancelled(true); }
    @EventHandler public void drop(PlayerDropItemEvent event) { if(blocked()) event.setCancelled(true); else active(event.getPlayer()); }
    @EventHandler public void pickup(EntityPickupItemEvent event) { if(blocked()) event.setCancelled(true); }
    @EventHandler public void swap(PlayerSwapHandItemsEvent event) { if(blocked()) event.setCancelled(true); else active(event.getPlayer()); }
    @EventHandler public void chat(AsyncChatEvent event) { if(blocked()) event.setCancelled(true); else active(event.getPlayer()); }
    @EventHandler(priority=EventPriority.HIGHEST) public void command(PlayerCommandPreprocessEvent event) {
        if(blocked() && !event.getMessage().toLowerCase(Locale.ROOT).startsWith("/smp ") && !event.getMessage().equalsIgnoreCase("/smp")) {
            event.setCancelled(true); event.getPlayer().sendMessage(Component.text("World closed. Use /smp commands in the queue screen."));
        }
    }
    public CommandContext context(CommandSender sender) {
        UUID id=sender instanceof Player player?player.getUniqueId():null;
        boolean admin=sender.hasPermission("smpmanager.admin") || sender.hasPermission("quorum.admin");
        boolean pass=admin || sender.hasPermission("smpmanager.autopass") || sender.hasPermission("quorum.autopass") || id!=null && settings.autopassAdmins.contains(id);
        return new CommandContext(id,sender.getName(),id==null?"console":"minecraft:"+id,admin,pass);
    }
    CommandContext queueContext(UUID id,String name) {
        Player player=Bukkit.getPlayer(id);
        if(player!=null) return context(player);
        boolean op=Bukkit.getOfflinePlayer(id).isOp();
        return new CommandContext(id,name,"minecraft:"+id,op,op || settings.autopassAdmins.contains(id));
    }
    public <T> T main(Callable<T> action) throws Exception {
        if(Bukkit.isPrimaryThread()) return action.call();
        if(!enabled) throw new IllegalStateException("SMP Manager is stopping");
        FutureTask<T> result=new FutureTask<>(action);
        var scheduled=Bukkit.getScheduler().runTask(this,result);
        try { return result.get(8,TimeUnit.SECONDS); }
        catch(ExecutionException failure) { if(failure.getCause() instanceof Exception cause) throw cause; throw failure; }
        catch(TimeoutException | InterruptedException failure) {
            result.cancel(false); scheduled.cancel();
            if(failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw failure;
        }
    }
    public void work(Callable<String> action,Consumer<String> response) {
        try { workers.execute(()->{
            String message;
            try { message=action.call(); }
            catch(Exception failure) { message=failure instanceof IllegalArgumentException || failure instanceof SecurityException?failure.getMessage():"Request failed. Check server availability and configuration ("+failure.getClass().getSimpleName()+")."; }
            response.accept(message==null?"Request failed.":message);
        }); } catch(RejectedExecutionException full) { response.accept("Too many pending requests. Try again shortly."); }
    }
    public void reply(CommandSender sender,String message) {
        if(!enabled) return;
        Bukkit.getScheduler().runTask(this,()->sender.sendMessage(Component.text(message)));
    }
    ManagerConfig settings() { return settings; }
    Store store() { return store; }
    ManagerEngine engine() { return engine; }
    VerificationService verification() { return verification; }
    ManagerCommands commands() { return commands; }
    boolean isEnabledManager() { return enabled; }
    boolean queuedAccountAllowed(UUID id) { return Bukkit.getOfflinePlayer(id).isWhitelisted(); }
    @Override public void onDisable() {
        enabled=false;
        try { Bukkit.getServerTickManager().setFrozen(true); } catch(Exception ignored) { }
        if(queue!=null) queue.close();
        for(Player player:new ArrayList<>(Bukkit.getOnlinePlayers())) player.kick(Component.text("SMP Manager is stopping; the world is saved and frozen."));
        try { if(engine!=null) engine.stopping(); saveWorld(); }
        catch(Exception failure) { if(engine!=null) engine.fail("World save during stop failed; repair storage and use /smp reset."); getLogger().severe("World save during stop failed; inspect logs before reopening."); }
        if(discord!=null) discord.close();
        workers.shutdownNow();
        try { workers.awaitTermination(3,TimeUnit.SECONDS); } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        try { if(store!=null) store.close(); if(lock!=null) lock.release(); if(lockChannel!=null) lockChannel.close(); } catch(Exception ignored) { }
    }
}
