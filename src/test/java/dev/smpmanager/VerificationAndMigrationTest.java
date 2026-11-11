package dev.smpmanager;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VerificationAndMigrationTest {
    @TempDir Path temp;
    Store store; ManagerEngineTest.FakeClock clock; ManagerEngineTest.Directory directory; ManagerConfig config; VerificationService verification;
    final String discord="123456789012345678",other="223456789012345678",key="test-verification-pepper-at-least-32-characters";
    @BeforeEach void setup() throws Exception {
        Path file=temp.resolve("settings.properties"); Files.writeString(file,""); config=new ManagerConfig(file);
        store=new Store(temp.resolve("data.db")); clock=new ManagerEngineTest.FakeClock(); directory=new ManagerEngineTest.Directory();
        verification=new VerificationService(store,directory,config,clock,key);
    }
    @AfterEach void close() throws Exception { store.close(); }
    @Test void codesAreSingleUseAndLinksRemainOneToOne() {
        String code=verification.issue(discord);
        verification.verify(ManagerEngineTest.id(1),"Player1",code);
        assertThrows(IllegalArgumentException.class,()->verification.verify(ManagerEngineTest.id(2),"Player2",code));
        String second=verification.issue(other);
        assertThrows(IllegalArgumentException.class,()->verification.verify(ManagerEngineTest.id(1),"Player1",second));
        assertEquals(1,verification.links(null,0).size());
    }
    @Test void expiredCodesAndPersistentAttemptLimitsCannotBeBypassedByRecreatingTheService() {
        String code=verification.issue(discord); clock.advance(300_000);
        assertThrows(IllegalArgumentException.class,()->verification.verify(ManagerEngineTest.id(1),"Player1",code));
        for(int n=0;n<4;n++) assertThrows(IllegalArgumentException.class,()->verification.verify(ManagerEngineTest.id(1),"Player1","invalid"));
        verification=new VerificationService(store,directory,config,clock,key);
        String fresh=verification.issue(discord);
        assertThrows(IllegalArgumentException.class,()->verification.verify(ManagerEngineTest.id(1),"Player1",fresh));
    }
    @Test void manualApprovalRequiresOriginalAdministratorAndExpires() {
        var candidate=verification.resolve("Player1",discord);
        var review=verification.prepare("minecraft:admin1",candidate); UUID token=(UUID)review.get("confirmation");
        assertThrows(IllegalArgumentException.class,()->verification.confirm("minecraft:admin2",token));
        verification.confirm("minecraft:admin1",token);
        assertEquals("manual",verification.links(discord,0).getFirst().method());
        var next=verification.prepare("minecraft:admin1",verification.resolve("Player2",other));
        clock.advance(300_000);
        assertThrows(IllegalArgumentException.class,()->verification.confirm("minecraft:admin1",(UUID)next.get("confirmation")));
    }
    @Test void revocationRemovesAssociationsReservationsAndOutstandingRequests() {
        verification.verify(ManagerEngineTest.id(1),"Player1",verification.issue(discord));
        store.execute("INSERT INTO discord_reservations VALUES(?,?,?,?,0)",ManagerEngineTest.id(1),discord,clock.millis(),clock.millis()+3_600_000);
        verification.unlink(discord,"admin");
        assertTrue(verification.links(discord,0).isEmpty()); assertTrue(store.rows("SELECT * FROM discord_reservations").isEmpty());
        assertEquals(1,store.rows("SELECT * FROM admin_audit WHERE action='UNVERIFY'").size());
    }
    @Test void legacySchemaLinksReservationsMessageIdAndCodeHashesRemainReadable() throws Exception {
        UUID id=ManagerEngineTest.id(1);
        store.execute("INSERT INTO linked_accounts VALUES(?,?,?,?,?)",id,"Player1",discord,clock.millis(),"automatic");
        store.execute("INSERT INTO discord_reservations VALUES(?,?,?,?,0)",id,discord,clock.millis(),clock.millis()+3_600_000);
        store.setMetadata("discord.status_message","323456789012345678");
        store.execute("INSERT INTO verification_codes VALUES(?,?,?,?,0)",Auth.hash(key,"123456"),other,clock.millis(),clock.millis()+300_000);
        store.close(); store=new Store(temp.resolve("data.db"));
        verification=new VerificationService(store,directory,config,clock,key);
        assertEquals(1,verification.links(discord,0).size());
        assertEquals(1,new ManagerEngine(store,config,directory,clock).snapshot().queueSize());
        assertEquals("323456789012345678",store.metadata("discord.status_message",""));
        verification.verify(ManagerEngineTest.id(2),"Player2","123456");
        assertEquals(2,verification.links(null,0).size());
        assertEquals("ok",Store.str(store.rows("PRAGMA integrity_check").getFirst(),"integrity_check"));
    }
}
