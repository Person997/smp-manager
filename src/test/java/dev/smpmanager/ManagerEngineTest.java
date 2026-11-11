package dev.smpmanager;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ManagerEngineTest {
    @TempDir Path temp;
    Store store; ManagerConfig config; ManagerEngine engine; FakeClock clock; Directory directory;
    final String discord="123456789012345678";
    static UUID id(int n) { return new UUID(0,n); }
    @BeforeEach void setup() throws Exception {
        config=config(""); store=new Store(temp.resolve("data.db")); clock=new FakeClock(); directory=new Directory();
        engine=new ManagerEngine(store,config,directory,clock); engine.sample(Map.of());
    }
    @AfterEach void close() throws Exception { store.close(); }
    ManagerConfig config(String extra) throws Exception {
        Path file=temp.resolve(UUID.randomUUID()+".properties");
        String defaults=new String(Objects.requireNonNull(getClass().getResourceAsStream("/settings.properties")).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        Files.writeString(file,defaults+"\n"+extra); return new ManagerConfig(file);
    }
    void waiting(int count) { for(int n=1;n<=count;n++) engine.waiting(id(n),"Player"+n,true); }
    void playing(int count) {
        Map<UUID,String> players=new HashMap<>();
        for(int n=1;n<=count;n++) { engine.joined(id(n)); players.put(id(n),"Player"+n); }
        engine.sample(players);
    }
    void active() { waiting(5); engine.tick(); playing(4); clock.advance(180_000); playing(4); engine.tick(); assertEquals(ManagerEngine.State.ACTIVE,engine.snapshot().state()); }
    void link(int n) { store.execute("INSERT INTO linked_accounts VALUES(?,?,?,?,?)",id(n),"Player"+n,discord,clock.millis(),"automatic"); }

    @Test void uniqueAccountsOpenOnceAndAnOpenWorldHasNoQueue() {
        waiting(4); link(1); engine.reserve(discord,false); engine.tick(); assertEquals(ManagerEngine.State.CLOSED,engine.snapshot().state());
        assertEquals(4,engine.snapshot().queueSize());
        engine.waiting(id(5),"Player5",true); engine.tick(); UUID session=engine.snapshot().session();
        assertEquals(ManagerEngine.State.JOINING,engine.snapshot().state()); assertEquals(0,engine.snapshot().queueSize());
        engine.tick(); assertEquals(session,engine.snapshot().session());
    }
    @Test void normalGraceIsFiveMinutesAndFourPlayersCancelIt() {
        active(); playing(3); engine.tick(); long deadline=engine.snapshot().deadline();
        assertEquals(clock.millis()+300_000,deadline);
        clock.advance(299_999); playing(3); engine.tick(); assertTrue(engine.isOpen());
        playing(4); engine.tick(); assertEquals(ManagerEngine.State.ACTIVE,engine.snapshot().state());
        clock.advance(1); playing(4); engine.tick(); assertTrue(engine.isOpen());
    }
    @Test void lowPopulationClosesAtTheDeadlineAndRearmsOnlyOnNewIntent() {
        active(); playing(3); engine.tick(); clock.advance(300_000); playing(3); engine.tick();
        assertEquals(ManagerEngine.State.CLOSED,engine.snapshot().state()); assertTrue(engine.snapshot().rearmRequired());
        for(int n=1;n<=5;n++) engine.waiting(id(n),"Player"+n,false);
        engine.tick(); assertEquals(ManagerEngine.State.CLOSED,engine.snapshot().state());
        engine.disconnected(id(1)); engine.waiting(id(1),"Player1",true); engine.tick(); assertTrue(engine.isOpen());
    }
    @Test void manualCloseCannotImmediatelyUndoItselfAndResetRestoresNormalQueue() {
        active(); engine.close("admin","Manual close");
        for(int n=1;n<=5;n++) engine.waiting(id(n),"Player"+n,false);
        engine.tick(); assertFalse(engine.isOpen());
        engine.reset("admin"); engine.tick(); assertEquals(ManagerEngine.State.JOINING,engine.snapshot().state());
        assertFalse(engine.snapshot().autopass());
    }
    @Test void failedInitialJoinWindowClosesAndDoesNotLoop() {
        waiting(5); engine.tick(); playing(1); clock.advance(180_000); playing(1); engine.tick();
        assertEquals(ManagerEngine.State.CLOSED,engine.snapshot().state());
        for(int n=1;n<=5;n++) engine.waiting(id(n),"Player"+n,false);
        engine.tick(); assertFalse(engine.isOpen());
    }
    @Test void oneSessionAutopassUsesAnHourWithoutRenewingOnRepeatedCommands() {
        engine.autopass("admin"); playing(1); long until=engine.snapshot().deadline();
        assertEquals(clock.millis()+3_600_000,until);
        clock.advance(300_000); playing(1); engine.tick(); assertTrue(engine.isOpen());
        engine.autopass("admin"); assertEquals(until,engine.snapshot().deadline());
        clock.advance(3_299_999); playing(1); engine.tick(); assertTrue(engine.isOpen());
        clock.advance(1); playing(1); engine.tick(); assertFalse(engine.isOpen());
        assertFalse(engine.snapshot().autopass()); assertEquals(300,engine.snapshot().graceSeconds());
    }
    @Test void autopassCountdownCancelsWithFourAndLaterLowPopulationGetsItsConfiguredGrace() {
        engine.autopass("admin"); playing(4); engine.tick(); assertEquals(ManagerEngine.State.ACTIVE,engine.snapshot().state());
        playing(3); engine.tick(); assertEquals(clock.millis()+3_600_000,engine.snapshot().deadline());
    }
    @Test void configurableThresholdsAndTimersAreActuallyApplied() throws Exception {
        ManagerConfig custom=config("players-required-to-open=3\nminimum-online-players=2\ngrace-period-seconds=17\nstartup-join-window-seconds=6\nautopass-grace-seconds=42\n");
        engine=new ManagerEngine(store,custom,directory,clock); waiting(3); engine.tick();
        assertEquals(clock.millis()+6000,engine.snapshot().deadline());
        playing(2); clock.advance(6000); playing(2); engine.tick(); assertEquals(ManagerEngine.State.ACTIVE,engine.snapshot().state());
        playing(1); engine.tick(); assertEquals(clock.millis()+17000,engine.snapshot().deadline());
        engine.close("admin","Close"); engine.autopass("admin"); assertEquals(clock.millis()+42000,engine.snapshot().deadline());
    }
    @Test void expiryIsAbsoluteAndJoiningMinecraftDoesNotRenewDiscordReservations() {
        link(1); long expires=engine.reserve(discord,false); clock.advance(120_000);
        assertEquals(expires,engine.reserve(discord,false)); engine.waiting(id(1),"Player1",true);
        assertEquals(1,engine.snapshot().queueSize());
        clock.advance(3_480_000); engine.tick(); assertEquals(1,engine.snapshot().queueSize());
        engine.disconnected(id(1)); assertEquals(0,engine.snapshot().queueSize());
        assertEquals(1,store.rows("SELECT * FROM events WHERE kind='RESERVATION_EXPIRED'").size());
    }
    @Test void renewalHasItsCooldownAndEnteringTheWorldConsumesAReservation() {
        link(1); engine.reserve(discord,false);
        assertThrows(IllegalArgumentException.class,()->engine.reserve(discord,true));
        clock.advance(300_000); assertEquals(clock.millis()+3_600_000,engine.reserve(discord,true));
        engine.joined(id(1)); assertTrue(store.rows("SELECT * FROM discord_reservations").isEmpty());
    }
    @Test void discordLeaveOnlyRemovesItsReservationWhileMinecraftIsAutomatic() {
        link(1); engine.reserve(discord,false); waiting(1); engine.leaveReservation(discord);
        assertEquals(1,engine.snapshot().queueSize()); engine.disconnected(id(1)); assertEquals(0,engine.snapshot().queueSize());
    }
    @Test void reservationAndLinksSurviveRestartButMinecraftPresenceDoesNot() {
        link(1); engine.reserve(discord,false); waiting(2);
        engine=new ManagerEngine(store,config,directory,clock);
        assertEquals(1,engine.snapshot().queueSize()); assertEquals(1,store.rows("SELECT * FROM linked_accounts").size());
    }
    @Test void unwhitelistedAndUnlinkedAccountsCannotQueue() {
        assertThrows(IllegalArgumentException.class,()->engine.waiting(id(99),"Player99",true));
        assertThrows(IllegalArgumentException.class,()->engine.reserve(discord,false));
        link(99); assertThrows(IllegalArgumentException.class,()->engine.reserve(discord,false));
    }
    @Test void staleSamplesNeverProveTheWorldHasEnoughPlayers() {
        active(); clock.advance(8000); engine.tick(); assertEquals(ManagerEngine.State.COUNTDOWN,engine.snapshot().state());
        assertFalse(engine.snapshot().confirmed()); assertEquals(0,engine.snapshot().online());
    }
    @Test void normalServerStopDoesNotCreateARecoveryError() {
        engine.autopass("admin"); engine.stopping();
        engine=new ManagerEngine(store,config,directory,clock);
        assertEquals(ManagerEngine.State.CLOSED,engine.snapshot().state()); assertFalse(engine.snapshot().autopass());
    }
    @Test void unexpectedRestartAndIncompleteSavesRemainFrozenUntilReset() {
        engine.autopass("admin"); engine=new ManagerEngine(store,config,directory,clock);
        assertEquals(ManagerEngine.State.ERROR,engine.snapshot().state());
        assertThrows(IllegalArgumentException.class,()->engine.autopass("admin"));
        engine.reset("admin"); assertEquals(ManagerEngine.State.CLOSED,engine.snapshot().state());
        store.setMetadata("manager.pending_save","true"); engine=new ManagerEngine(store,config,directory,clock);
        assertEquals(ManagerEngine.State.ERROR,engine.snapshot().state());
    }
    @Test void openingByAutopassDoesNotAllowNewDiscordReservationsWhileTheWorldIsOpen() {
        link(1); engine.autopass("admin");
        assertThrows(IllegalArgumentException.class,()->engine.reserve(discord,false)); assertEquals(0,engine.snapshot().queueSize());
    }
    static class FakeClock extends Clock {
        long now=1_800_000_000_000L;
        void advance(long ms) { now+=ms; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(now); }
        public long millis() { return now; }
    }
    static class Directory implements Identities {
        public boolean whitelisted(UUID id) { return id.getMostSignificantBits()==0 && id.getLeastSignificantBits()>0 && id.getLeastSignificantBits()<=40; }
        public String name(UUID id) { return "Player"+id.getLeastSignificantBits(); }
        public Minecraft resolveMinecraft(String name) { UUID uuid=id(Integer.parseInt(name.substring(6))); if(!whitelisted(uuid)) throw new IllegalArgumentException("Not whitelisted"); return new Minecraft(uuid,name); }
        public String resolveDiscord(String id) { return Wire.snowflake(id); }
    }
}
