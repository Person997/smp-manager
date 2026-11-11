package dev.smpmanager;


import java.security.SecureRandom;
import java.time.Clock;
import java.util.*;

public final class VerificationService {
    private final Store store;
    private final Identities identities;
    private final ManagerConfig settings;
    private final Clock clock;
    private final String pepper;
    private final SecureRandom random = new SecureRandom();
    public VerificationService(Store store, Identities identities, ManagerConfig settings, Clock clock, String pepper) {
        this.store = store; this.identities = identities; this.settings = settings; this.clock = clock; this.pepper = pepper;
    }
    public String issue(String discord) {
        Wire.snowflake(discord); long now = clock.millis();
        return store.transaction(() -> {
            if (!store.rows("SELECT 1 FROM linked_accounts WHERE discord_id=?",discord).isEmpty())
                throw new IllegalArgumentException("Already linked; unlink before changing accounts");
            var requests = store.rows("SELECT next_allowed FROM verification_requests WHERE discord_id=?",discord);
            if (!requests.isEmpty() && Store.num(requests.getFirst(),"next_allowed") > now)
                throw new IllegalArgumentException("Wait before requesting another code");
            String code = null, hash = null;
            for (int i=0;i<100;i++) {
                code = String.format(Locale.ROOT,"%06d",random.nextInt(1_000_000)); hash = hash(code);
                if (store.rows("SELECT 1 FROM verification_codes WHERE code_hash=?",hash).isEmpty()) break;
                code = null;
            }
            if (code == null) throw new IllegalArgumentException("Verification code capacity reached; administrator assistance required");
            store.execute("UPDATE verification_codes SET invalidated=1 WHERE discord_id=?",discord);
            store.execute("INSERT INTO verification_codes VALUES(?,?,?,?,0)",hash,discord,now,now+300_000);
            store.execute("INSERT INTO verification_requests VALUES(?,?) ON CONFLICT(discord_id) DO UPDATE SET next_allowed=excluded.next_allowed",
                    discord,now+settings.number("verification.request_cooldown_seconds",30)*1000L);
            return code;
        });
    }
    public Wire.Link verify(UUID minecraft, String name, String code) {
        if (!identities.whitelisted(minecraft)) throw new IllegalArgumentException("Minecraft account is not whitelisted");
        long now = clock.millis();
        // Attempts are committed even when validation fails, and survive restarts.
        var rows = store.rows("SELECT * FROM verification_attempts WHERE minecraft_uuid=?",minecraft);
        long window = now, blocked = 0, count = 0;
        if (!rows.isEmpty()) {
            var attempt = rows.getFirst(); blocked = Store.num(attempt,"blocked_until");
            if (blocked > now) throw new IllegalArgumentException("Too many attempts; wait five minutes");
            window = Store.num(attempt,"window_start");
            if (now-window < 300_000) count = Store.num(attempt,"attempts"); else window=now;
        }
        if (count >= 5) throw new IllegalArgumentException("Too many attempts; wait five minutes");
        count++;
        store.execute("INSERT INTO verification_attempts VALUES(?,?,?,?) ON CONFLICT(minecraft_uuid) DO UPDATE SET window_start=excluded.window_start,attempts=excluded.attempts,blocked_until=excluded.blocked_until",
                minecraft,window,count,count>=5?now+300_000:0);
        if (code == null || !code.matches("[0-9]{6}")) throw new IllegalArgumentException("Invalid or expired verification code");
        return store.transaction(() -> {
            var tokens = store.rows("SELECT * FROM verification_codes WHERE code_hash=? AND invalidated=0 AND expires_at>?",hash(code),now);
            if (tokens.isEmpty()) throw new IllegalArgumentException("Invalid or expired verification code");
            String discord = Store.str(tokens.getFirst(),"discord_id");
            Wire.Link link = associate(minecraft,name,discord,"minecraft:"+minecraft,"automatic");
            store.execute("DELETE FROM verification_attempts WHERE minecraft_uuid=?",minecraft);
            return link;
        });
    }
    public record Candidate(Identities.Minecraft minecraft,String discord,String discordName) { }
    public Candidate resolve(String username,String discord) {
        if (!settings.bool("verification.allow_manual_admin_verification",true)) throw new IllegalArgumentException("Manual verification disabled");
        Wire.snowflake(discord);
        Identities.Minecraft account = identities.resolveMinecraft(username);
        String discordName = identities.resolveDiscord(discord);
        return new Candidate(account,discord,discordName);
    }
    public Map<String,Object> prepare(String actor, Candidate candidate) {
        Identities.Minecraft account=candidate.minecraft(); String discord=candidate.discord(),discordName=candidate.discordName();
        if(!identities.whitelisted(account.uuid())) throw new IllegalArgumentException("Minecraft account is no longer whitelisted");
        conflict(account.uuid(),discord);
        UUID id = UUID.randomUUID(); long expires = clock.millis()+300_000;
        store.transaction(() -> {
            store.execute("DELETE FROM pending_verifications WHERE actor=? OR expires_at<=?",actor,clock.millis());
            store.execute("INSERT INTO pending_verifications VALUES(?,?,?,?,?,?,?)",id,actor,account.uuid(),account.name(),discord,discordName,expires);
            store.audit(actor,"MANUAL_PREPARE",account.uuid(),discord,clock.millis(),"Administrator assertion; awaiting explicit confirmation");
            return null;
        });
        return Map.of("confirmation",id,"uuid",account.uuid(),"minecraft",account.name(),"discord",discord,"discordName",discordName,"expires",expires);
    }
    public Wire.Link confirm(String actor, UUID confirmation) {
        if (!settings.bool("verification.allow_manual_admin_verification",true)) throw new IllegalArgumentException("Manual verification disabled");
        return store.transaction(() -> {
            var rows = store.rows("SELECT * FROM pending_verifications WHERE id=? AND actor=? AND expires_at>?",confirmation,actor,clock.millis());
            if (rows.isEmpty()) throw new IllegalArgumentException("Confirmation is invalid, expired, or belongs to another administrator");
            var row = rows.getFirst(); UUID uuid = UUID.fromString(Store.str(row,"minecraft_uuid"));
            if (!identities.whitelisted(uuid)) throw new IllegalArgumentException("Minecraft account is no longer whitelisted");
            Wire.Link link = associate(uuid,Store.str(row,"minecraft_name"),Store.str(row,"discord_id"),actor,"manual");
            store.execute("DELETE FROM pending_verifications WHERE minecraft_uuid=? OR discord_id=?",uuid,link.discordId());
            return link;
        });
    }
    private void conflict(UUID uuid, String discord) {
        if (!store.rows("SELECT 1 FROM linked_accounts WHERE minecraft_uuid=? OR discord_id=?",uuid,discord).isEmpty())
            throw new IllegalArgumentException("An identity is already linked. Inspect verification status and explicitly unverify the incorrect association first.");
    }
    private Wire.Link associate(UUID uuid, String name, String discord, String actor, String method) {
        conflict(uuid,discord); long now=clock.millis();
        store.execute("INSERT INTO linked_accounts VALUES(?,?,?,?,?)",uuid,name,discord,now,method);
        store.execute("UPDATE verification_codes SET invalidated=1 WHERE discord_id=?",discord);
        store.execute("DELETE FROM pending_verifications WHERE minecraft_uuid=? OR discord_id=?",uuid,discord);
        store.audit(actor,method.equals("manual")?"MANUAL_VERIFY":"VERIFY",uuid,discord,now,"Link created using "+method+" verification");
        store.event(now,"LINKED","Your Discord account is now linked to Minecraft " + name + " (" + uuid + ") via " + method + " verification.",discord);
        return new Wire.Link(uuid,name,discord,now,method);
    }
    public void unlink(String discord, String actor) {
        Wire.snowflake(discord); long now=clock.millis();
        store.transaction(() -> {
            var links = store.rows("SELECT minecraft_uuid FROM linked_accounts WHERE discord_id=?",discord);
            UUID uuid=links.isEmpty()?null:UUID.fromString(Store.str(links.getFirst(),"minecraft_uuid"));
            store.execute("UPDATE verification_codes SET invalidated=1 WHERE discord_id=?",discord);
            store.execute("DELETE FROM pending_verifications WHERE discord_id=?",discord);
            store.execute("DELETE FROM discord_reservations WHERE discord_id=?",discord);
            store.execute("DELETE FROM linked_accounts WHERE discord_id=?",discord);
            store.audit(actor,"UNVERIFY",uuid,discord,now,"Link revoked and outstanding tokens invalidated");
            store.event(now,"UNLINKED","Your SMP Manager Discord verification was revoked.",discord); return null;
        });
    }
    public List<Wire.Link> links(String discord, int page) {
        String where = discord == null ? "" : " WHERE discord_id=?";
        Object[] args = discord == null ? new Object[]{Math.max(0,page)*20} : new Object[]{Wire.snowflake(discord),Math.max(0,page)*20};
        return store.rows("SELECT * FROM linked_accounts"+where+" ORDER BY created_at DESC LIMIT 20 OFFSET ?",args).stream()
                .map(r -> new Wire.Link(UUID.fromString(Store.str(r,"minecraft_uuid")),Store.str(r,"minecraft_name"),Store.str(r,"discord_id"),Store.num(r,"created_at"),Store.str(r,"method"))).toList();
    }
    public List<Wire.Audit> history(String discord, int page) {
        String where=discord==null?"":" WHERE discord_id=?";
        Object[] args=discord==null?new Object[]{Math.max(0,page)*20}:new Object[]{Wire.snowflake(discord),Math.max(0,page)*20};
        return store.rows("SELECT * FROM admin_audit"+where+" ORDER BY id DESC LIMIT 20 OFFSET ?",args).stream()
                .map(r -> new Wire.Audit(Store.num(r,"id"),Store.str(r,"actor"),Store.str(r,"action"),Store.str(r,"minecraft_uuid"),Store.str(r,"discord_id"),Store.num(r,"timestamp"),Store.str(r,"detail"))).toList();
    }
    public Object pending() {
        return Map.of("associations",store.rows("SELECT * FROM pending_verifications WHERE expires_at>? ORDER BY expires_at LIMIT 50",clock.millis()),
                "codes",store.rows("SELECT discord_id,created_at,expires_at FROM verification_codes WHERE invalidated=0 AND expires_at>? LIMIT 50",clock.millis()));
    }
    public void cleanup() {
        long now=clock.millis();
        store.transaction(() -> {
            store.execute("UPDATE verification_codes SET invalidated=1 WHERE expires_at<=? AND invalidated=0",now);
            store.execute("DELETE FROM pending_verifications WHERE expires_at<=?",now);
            store.execute("DELETE FROM verification_attempts WHERE window_start<? AND blocked_until<?",now-300_000,now);
            return null;
        });
    }
    private String hash(String code) { return Auth.hash(pepper,code); }
}
