package dev.smpmanager;


import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.function.Supplier;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/** One connection, serialized transactions, SQLite constraints as the final consistency boundary. */
public final class Store implements AutoCloseable {
    private final Connection connection;
    public Store(Path database) throws Exception {
        Files.createDirectories(database.toAbsolutePath().getParent());
        Class.forName("org.sqlite.JDBC");
        connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
        execute("PRAGMA journal_mode=WAL"); execute("PRAGMA foreign_keys=ON");
        execute("PRAGMA synchronous=FULL"); execute("PRAGMA busy_timeout=5000");
        execute("CREATE TABLE IF NOT EXISTS schema_migrations(version INTEGER PRIMARY KEY, checksum TEXT NOT NULL)");
        byte[] source;
        try (var stream = Store.class.getResourceAsStream("/db/V001__initial.sql")) {
            source = Objects.requireNonNull(stream, "Missing migration").readAllBytes();
        }
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source));
        var applied = rows("SELECT * FROM schema_migrations ORDER BY version");
        if (applied.size() > 1 || (!applied.isEmpty() && (num(applied.getFirst(), "version") != 1 || !checksum.equals(str(applied.getFirst(), "checksum")))))
            throw new IllegalStateException("Unknown or modified database migration; restore the matching release");
        if (applied.isEmpty()) transaction(() -> {
            for (String sql : new String(source, StandardCharsets.UTF_8).split(";")) if (!sql.isBlank()) execute(sql);
            execute("INSERT INTO schema_migrations VALUES(1,?)", checksum); return null;
        });
    }
    public synchronized int execute(String sql, Object... args) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, args); statement.execute(); return Math.max(0,statement.getUpdateCount());
        } catch (SQLException e) { throw new IllegalStateException("Database operation failed", e); }
    }
    public synchronized List<Map<String, Object>> rows(String sql, Object... args) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, args);
            try (ResultSet result = statement.executeQuery()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                while (result.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= result.getMetaData().getColumnCount(); i++) row.put(result.getMetaData().getColumnLabel(i), result.getObject(i));
                    rows.add(row);
                }
                return rows;
            }
        } catch (SQLException e) { throw new IllegalStateException("Database query failed", e); }
    }
    public synchronized <T> T transaction(Supplier<T> work) {
        try {
            if (!connection.getAutoCommit()) return work.get();
            connection.setAutoCommit(false);
            try { T result = work.get(); connection.commit(); return result; }
            catch (RuntimeException | Error e) { connection.rollback(); throw e; }
            finally { connection.setAutoCommit(true); }
        } catch (SQLException e) { throw new IllegalStateException("Database transaction failed", e); }
    }
    private static void bind(PreparedStatement statement, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i] instanceof UUID ? args[i].toString() : args[i]);
    }
    public String metadata(String key, String fallback) {
        var rows = rows("SELECT value FROM config_metadata WHERE key=?", key);
        return rows.isEmpty() ? fallback : str(rows.getFirst(), "value");
    }
    public void setMetadata(String key, String value) { execute("INSERT INTO config_metadata VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value", key, value); }
    public void audit(String actor, String action, UUID uuid, String discord, long now, String detail) {
        execute("INSERT INTO admin_audit(actor,action,minecraft_uuid,discord_id,timestamp,detail) VALUES(?,?,?,?,?,?)",
                actor, action, uuid == null ? "" : uuid, discord == null ? "" : discord, now, detail);
    }
    public void event(long now, String kind, String message, String discord) {
        execute("INSERT INTO events(timestamp,kind,message,discord_id) VALUES(?,?,?,?)", now, kind, message, discord);
    }
    public List<Wire.Event> events(long after) {
        return rows("SELECT * FROM events WHERE id>? ORDER BY id LIMIT 50", after).stream()
                .map(r -> new Wire.Event(num(r,"id"),num(r,"timestamp"),str(r,"kind"),str(r,"message"),str(r,"discord_id"))).toList();
    }
    public static String str(Map<String, Object> row, String key) { return row.get(key) == null ? null : row.get(key).toString(); }
    public static long num(Map<String, Object> row, String key) { return ((Number)row.get(key)).longValue(); }
    @Override public synchronized void close() throws SQLException { connection.close(); }
}
