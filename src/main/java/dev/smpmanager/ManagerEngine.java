package dev.smpmanager;

import java.time.Clock;
import java.util.*;
import static dev.smpmanager.Store.*;

/** One serialized authority inside Paper. No external process, host API or RPC keys. */
public final class ManagerEngine {
    public enum State { CLOSED,JOINING,ACTIVE,COUNTDOWN,ERROR }
    private final Store store;
    private final ManagerConfig config;
    private final Identities identities;
    private final Clock clock;
    private final Map<UUID,String> waiting=new HashMap<>();
    private Map<UUID,String> eligible=Map.of();
    private long sampleTime,lastCleanup;
    private State state=State.CLOSED;
    private UUID session;
    private long deadline;
    private boolean autopass,rearmRequired;
    private String detail="Waiting for enough players.";

    public ManagerEngine(Store store,ManagerConfig config,Identities identities,Clock clock) {
        this.store=store; this.config=config; this.identities=identities; this.clock=clock;
        String fault=store.metadata("manager.error","");
        String previousState=store.metadata("manager.state","");
        if(fault.isBlank() && (Set.of("JOINING","ACTIVE","COUNTDOWN").contains(previousState) || store.metadata("manager.pending_save","").equals("true"))) {
            fault="Previous server exit or save was not confirmed. Check logs/world storage and use /smp reset.";
            store.setMetadata("manager.error",fault);
        }
        if(!fault.isBlank()) { state=State.ERROR; detail=fault; }
        store.setMetadata("manager.state",state.name());
    }
    public synchronized void waiting(UUID id,String name,boolean newIntent) {
        if(!identities.whitelisted(id)) throw new IllegalArgumentException("Minecraft account is not whitelisted");
        waiting.put(id,name); if(newIntent) rearmRequired=false;
    }
    public synchronized void disconnected(UUID id) { waiting.remove(id); }
    public synchronized void joined(UUID id) {
        waiting.remove(id);
        store.execute("DELETE FROM discord_reservations WHERE minecraft_uuid=?",id);
    }
    public synchronized void sample(Map<UUID,String> players) {
        Map<UUID,String> checked=new HashMap<>();
        players.forEach((id,name)->{ if(identities.whitelisted(id)) checked.put(id,name); });
        eligible=Map.copyOf(checked); sampleTime=clock.millis();
    }
    public synchronized void tick() {
        long now=clock.millis();
        if(now-lastCleanup>=1000) { expire(); lastCleanup=now; }
        switch(state) {
            case CLOSED -> { if(!rearmRequired && members().size()>=config.startPlayers) open(false,"system"); }
            case JOINING -> {
                if(now>=deadline) {
                    if(online()>=config.minimumPlayers) transition(State.ACTIVE,0,"Player threshold met.","ACTIVE");
                    else close("system","Initial join window ended below the player threshold.");
                }
            }
            case ACTIVE -> { if(online()<config.minimumPlayers) transition(State.COUNTDOWN,now+graceMs(),"Player threshold not met.","COUNTDOWN"); }
            case COUNTDOWN -> {
                if(online()>=config.minimumPlayers) transition(State.ACTIVE,0,"Player threshold met; world closing canceled.","CANCELED");
                else if(now>=deadline) close("system","Player threshold remained unmet; world saved and frozen.");
            }
            case ERROR -> { }
        }
    }
    private void open(boolean override,String actor) {
        if(state!=State.CLOSED) throw new IllegalArgumentException("World is not in a closed state");
        session=UUID.randomUUID(); autopass=override; rearmRequired=false;
        store.audit(actor,override?"AUTOPASS":"OPEN",null,null,clock.millis(),"session="+session);
        transition(override?State.COUNTDOWN:State.JOINING,clock.millis()+(override?config.autopassSeconds:config.joinSeconds)*1000L,
                override?"Admin autopass enabled for this session.":"Initial join window opened.","ONLINE");
    }
    public synchronized String autopass(String actor) {
        if(state==State.ERROR) throw new IllegalArgumentException("Resolve the world fault and use /smp reset first");
        if(autopass) return "Autopass is already enabled for this session; its timer was not extended.";
        if(state==State.CLOSED) open(true,actor);
        else {
            autopass=true;
            store.audit(actor,"AUTOPASS",null,null,clock.millis(),"session="+session);
            if(online()>=config.minimumPlayers) transition(State.ACTIVE,0,"Admin autopass enabled for this session.","OVERRIDE");
            else transition(State.COUNTDOWN,clock.millis()+graceMs(),"Admin autopass enabled for this session.","OVERRIDE");
        }
        return "Autopass enabled for this session. Low-population grace: "+config.autopassSeconds+" seconds.";
    }
    public synchronized void close(String actor,String reason) {
        if(state==State.ERROR) throw new IllegalArgumentException("World is already frozen for a fault; use /smp reset after repair");
        boolean changed=state!=State.CLOSED;
        store.audit(actor,"CLOSE",null,null,clock.millis(),reason);
        state=State.CLOSED; session=null; deadline=0; autopass=false; rearmRequired=true; detail=reason;
        store.setMetadata("manager.state",state.name());
        if(changed) store.event(clock.millis(),"CLOSED",reason,null);
    }
    public synchronized void fail(String reason) {
        state=State.ERROR; deadline=0; detail=reason; rearmRequired=true;
        store.setMetadata("manager.error",reason); store.setMetadata("manager.state",state.name());
        store.event(clock.millis(),"FAILED",reason,null);
    }
    public synchronized String reset(String actor) {
        store.audit(actor,"RESET",null,null,clock.millis(),"World save verified; queue re-armed");
        state=State.CLOSED; session=null; deadline=0; autopass=false; rearmRequired=false; detail="Queue re-armed; waiting for enough players.";
        store.setMetadata("manager.error",""); store.setMetadata("manager.state",state.name());
        return "World remains frozen. Fault/close hold cleared; normal queue rules are active.";
    }
    public synchronized void stopping() {
        if(state!=State.ERROR) { state=State.CLOSED; deadline=0; session=null; autopass=false; detail="Minecraft server stopped."; store.setMetadata("manager.state",state.name()); }
    }
    public synchronized long reserve(String discord,boolean renew) {
        if(snapshot().worldOpen()) throw new IllegalArgumentException("The world is open. Join Minecraft directly; no queue is needed.");
        UUID id=linked(discord); require(id); long now=clock.millis();
        var rows=store.rows("SELECT * FROM discord_reservations WHERE minecraft_uuid=? AND expires_at>?",id,now);
        if(!rows.isEmpty()) {
            var row=rows.getFirst();
            if(!renew) { rearmRequired=false; return num(row,"expires_at"); }
            if(now-Math.max(num(row,"created_at"),num(row,"renewed_at"))<config.renewSeconds*1000L)
                throw new IllegalArgumentException("Wait "+config.renewSeconds+" seconds between creation/renewals");
        }
        long expires=now+config.reservationMinutes*60_000L;
        store.execute("INSERT INTO discord_reservations VALUES(?,?,?,?,?) ON CONFLICT(minecraft_uuid) DO UPDATE SET discord_id=excluded.discord_id,created_at=excluded.created_at,expires_at=excluded.expires_at,renewed_at=excluded.renewed_at",id,discord,now,expires,renew?now:0);
        rearmRequired=false; return expires;
    }
    public synchronized String leaveReservation(String discord) {
        UUID id=linked(discord); store.execute("DELETE FROM discord_reservations WHERE minecraft_uuid=?",id);
        return "Discord reservation removed. A Minecraft account still connected to the waiting screen counts automatically.";
    }
    private UUID linked(String discord) {
        Wire.snowflake(discord); var rows=store.rows("SELECT minecraft_uuid FROM linked_accounts WHERE discord_id=?",discord);
        if(rows.isEmpty()) throw new IllegalArgumentException("Link your Minecraft account first with /link in Discord");
        return UUID.fromString(str(rows.getFirst(),"minecraft_uuid"));
    }
    private void require(UUID id) { if(!identities.whitelisted(id)) throw new IllegalArgumentException("Minecraft account is not whitelisted"); }
    private Set<UUID> members() {
        Set<UUID> result=new HashSet<>();
        waiting.keySet().stream().filter(identities::whitelisted).forEach(result::add);
        for(var row:store.rows("SELECT minecraft_uuid FROM discord_reservations WHERE expires_at>?",clock.millis())) {
            UUID id=UUID.fromString(str(row,"minecraft_uuid")); if(identities.whitelisted(id)) result.add(id);
        }
        return result;
    }
    private void expire() {
        long now=clock.millis();
        store.transaction(()->{
            for(var row:store.rows("SELECT discord_id FROM discord_reservations WHERE expires_at<=?",now))
                store.event(now,"RESERVATION_EXPIRED","Your SMP Manager reservation expired. Use /queue join in Discord to return.",str(row,"discord_id"));
            store.execute("DELETE FROM discord_reservations WHERE expires_at<=?",now); return null;
        });
    }
    private int online() { return clock.millis()-sampleTime<8000?eligible.size():0; }
    private long graceMs() { return (autopass?config.autopassSeconds:config.graceSeconds)*1000L; }
    private void transition(State next,long until,String reason,String event) {
        state=next; deadline=until; detail=reason; store.setMetadata("manager.state",state.name());
        store.event(clock.millis(),event,reason,null);
    }
    public synchronized boolean isOpen() { return Set.of(State.JOINING,State.ACTIVE,State.COUNTDOWN).contains(state); }
    public synchronized ManagerSnapshot snapshot() {
        boolean confirmed=clock.millis()-sampleTime<8000;
        boolean open=Set.of(State.JOINING,State.ACTIVE,State.COUNTDOWN).contains(state);
        return new ManagerSnapshot(state,session,open?0:members().size(),config.startPlayers,config.minimumPlayers,
                open&&confirmed?eligible.size():0,open&&confirmed?eligible.values().stream().sorted().toList():List.of(),deadline,sampleTime,confirmed,
                autopass,(int)(graceMs()/1000),rearmRequired,detail);
    }
}
