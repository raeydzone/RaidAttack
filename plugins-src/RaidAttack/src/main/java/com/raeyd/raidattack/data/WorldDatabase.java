package com.raeyd.raidattack.data;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Persistence for gameplay state in the {@code world} schema of the raidattack database — the
 * replacement for the plugin's legacy per-feature {@code .yml} files. Tables are owned by the
 * plugin's role ({@code ra_plugin} owns schema {@code world}); auth tables in {@code public} are
 * untouched here.
 *
 * Pattern (per the migration): managers keep their in-memory state, load() it once at startup from
 * here, and persist individual changes via these methods on an async thread (never the main tick).
 * Tables are added to {@link #ensureSchema()} as each manager is migrated off YAML.
 */
public final class WorldDatabase {

    private final HikariDataSource ds;
    private final Logger log;

    public WorldDatabase(HikariDataSource ds, Logger log) {
        this.ds = ds;
        this.log = log;
    }

    public void ensureSchema() {
        // The `world` schema itself is provisioned out-of-band (owned by ra_plugin). Here we only
        // create the tables, idempotently. New gameplay tables get appended as managers migrate.
        exec("""
            CREATE TABLE IF NOT EXISTS world.armor_indicator (
                player_rid UUID PRIMARY KEY
            )
            """);
        exec("""
            CREATE TABLE IF NOT EXISTS world.quest_progress (
                player_rid UUID NOT NULL,
                quest_key   TEXT NOT NULL,
                amount      INTEGER NOT NULL,
                PRIMARY KEY (player_rid, quest_key)
            )
            """);
        exec("""
            CREATE TABLE IF NOT EXISTS world.quest_rewarded (
                player_rid UUID PRIMARY KEY
            )
            """);
        exec("""
            CREATE TABLE IF NOT EXISTS world.server_rights (
                role       TEXT NOT NULL CHECK (role IN ('owner','admin')),
                identifier TEXT NOT NULL
            )
            """);
        // Case-insensitive uniqueness per role (entries are name-or-UUID, matched ignoring case).
        exec("CREATE UNIQUE INDEX IF NOT EXISTS ux_server_rights ON world.server_rights (role, LOWER(identifier))");
        exec("""
            CREATE TABLE IF NOT EXISTS world.alliances (
                id                BIGSERIAL PRIMARY KEY,
                name              TEXT NOT NULL,
                name_lower        TEXT NOT NULL UNIQUE,
                color             TEXT NOT NULL,
                leader_rid       UUID NOT NULL,
                created_at_millis BIGINT NOT NULL
            )
            """);
        // Members + join-requests reference the alliance by its stable id (single source of truth);
        // disband cascades both away. A player is in at most one alliance — enforced in code, but the
        // reverse index here keeps "who is in what" a fast lookup.
        exec("""
            CREATE TABLE IF NOT EXISTS world.alliance_members (
                alliance_id BIGINT NOT NULL REFERENCES world.alliances(id) ON DELETE CASCADE,
                member_rid UUID NOT NULL,
                PRIMARY KEY (alliance_id, member_rid)
            )
            """);
        exec("CREATE INDEX IF NOT EXISTS ix_alliance_members_uuid ON world.alliance_members (member_rid)");
        exec("""
            CREATE TABLE IF NOT EXISTS world.alliance_requests (
                alliance_id    BIGINT NOT NULL REFERENCES world.alliances(id) ON DELETE CASCADE,
                applicant_rid UUID NOT NULL,
                PRIMARY KEY (alliance_id, applicant_rid)
            )
            """);
        // ---- claims + turrets + friends (one claim per owner; children FK→claim, cascade) ----
        exec("""
            CREATE TABLE IF NOT EXISTS world.claims (
                owner_rid          UUID PRIMARY KEY,
                world_uuid          UUID NOT NULL,
                min_x INT NOT NULL, min_z INT NOT NULL, max_x INT NOT NULL, max_z INT NOT NULL,
                attack_hostile_mobs BOOLEAN NOT NULL DEFAULT TRUE
            )
            """);
        // Per-slot level + downtime live even when the slot is empty (level persists across redeploy).
        exec("""
            CREATE TABLE IF NOT EXISTS world.claim_slots (
                owner_rid        UUID NOT NULL REFERENCES world.claims(owner_rid) ON DELETE CASCADE,
                slot              SMALLINT NOT NULL,
                level             SMALLINT NOT NULL DEFAULT 1,
                respawn_at_millis BIGINT NOT NULL DEFAULT 0,
                PRIMARY KEY (owner_rid, slot)
            )
            """);
        exec("""
            CREATE TABLE IF NOT EXISTS world.claim_turrets (
                owner_rid        UUID NOT NULL REFERENCES world.claims(owner_rid) ON DELETE CASCADE,
                slot              SMALLINT NOT NULL,
                x INT NOT NULL, y INT NOT NULL, z INT NOT NULL,
                npc_id            INT NOT NULL DEFAULT -1,
                structure_hp      INT NOT NULL DEFAULT 300,
                respawn_at_millis BIGINT NOT NULL DEFAULT 0,
                PRIMARY KEY (owner_rid, slot)
            )
            """);
        exec("""
            CREATE TABLE IF NOT EXISTS world.claim_friends (
                owner_rid  UUID NOT NULL REFERENCES world.claims(owner_rid) ON DELETE CASCADE,
                friend_rid UUID NOT NULL,
                PRIMARY KEY (owner_rid, friend_rid)
            )
            """);
        // Slot levels preserved for a FORMER owner after /unclaim, restored on re-claim. No FK to
        // claims on purpose (the claim row is gone while this is held).
        exec("""
            CREATE TABLE IF NOT EXISTS world.saved_slot_levels (
                owner_rid UUID NOT NULL,
                slot       SMALLINT NOT NULL,
                level      SMALLINT NOT NULL,
                PRIMARY KEY (owner_rid, slot)
            )
            """);
        // RID→name cache and the "held in adventure" saved gamemode (both per-player, keyed by RID).
        exec("CREATE TABLE IF NOT EXISTS world.player_names (rid UUID PRIMARY KEY, name TEXT NOT NULL)");
        exec("CREATE TABLE IF NOT EXISTS world.player_gamemodes (rid UUID PRIMARY KEY, gamemode TEXT NOT NULL)");
        // Raids: ACTIVE raid state is intentionally NOT persisted (wiped on every enable by design).
        // Only the 36h per-zone cooldown and the per-player loot stash survive restarts.
        exec("CREATE TABLE IF NOT EXISTS world.raid_cooldowns (zone_owner_rid UUID PRIMARY KEY, until_millis BIGINT NOT NULL)");
        exec("CREATE TABLE IF NOT EXISTS world.raid_inventories (owner_rid UUID PRIMARY KEY, contents BYTEA NOT NULL)");
        // Combat-log ("tactical leaving") pending punishments. A row = the player quit low-HP while
        // chased. `inventory` is the quit-time snapshot dropped at the spot when the 10-min timer
        // expires (victim usually offline); `executed` = items already dropped, wipe the player's
        // inventory at their next /login so nothing duplicates. DB so restarts don't pardon.
        exec("""
            CREATE TABLE IF NOT EXISTS world.tactical_leaves (
                victim_rid     UUID PRIMARY KEY,
                attacker_name  TEXT NOT NULL,
                world_uuid     UUID NOT NULL,
                x DOUBLE PRECISION NOT NULL,
                y DOUBLE PRECISION NOT NULL,
                z DOUBLE PRECISION NOT NULL,
                quit_at_millis BIGINT NOT NULL,
                inventory      BYTEA,
                executed       BOOLEAN NOT NULL DEFAULT FALSE
            )
            """);
        // Upgrade path for tables created before the expiry-time execution model.
        exec("ALTER TABLE world.tactical_leaves ADD COLUMN IF NOT EXISTS inventory BYTEA");
        exec("ALTER TABLE world.tactical_leaves ADD COLUMN IF NOT EXISTS executed BOOLEAN NOT NULL DEFAULT FALSE");
        log.info("World schema ensured (gameplay tables).");
    }

    private void exec(String sql) {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new RuntimeException("world ensureSchema failed: " + e.getMessage(), e);
        }
    }

    // ---- armor durability indicator (per-player opt-in) ----------------------------------
    // Presence of a row = indicator ON. Replaces armor_indicator.yml.

    public Set<UUID> loadArmorIndicatorOn() {
        Set<UUID> out = new HashSet<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT player_rid FROM world.armor_indicator");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(rs.getObject(1, UUID.class));
        } catch (SQLException e) {
            log.warning("world: loadArmorIndicatorOn failed: " + e.getMessage());
        }
        return out;
    }

    public void setArmorIndicator(UUID playerId, boolean on) {
        String sql = on
                ? "INSERT INTO world.armor_indicator (player_rid) VALUES (?) ON CONFLICT DO NOTHING"
                : "DELETE FROM world.armor_indicator WHERE player_rid = ?";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, playerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warning("world: setArmorIndicator failed for " + playerId + ": " + e.getMessage());
        }
    }

    // ---- quests (per-player achievement progress) ----------------------------------------
    // Replaces quests.yml. Progress only grows, so writes are pure upserts (no deletes).

    public Map<UUID, Map<String, Integer>> loadQuestProgress() {
        Map<UUID, Map<String, Integer>> out = new HashMap<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT player_rid, quest_key, amount FROM world.quest_progress");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.computeIfAbsent(rs.getObject(1, UUID.class), k -> new HashMap<>())
                   .put(rs.getString(2), rs.getInt(3));
            }
        } catch (SQLException e) {
            log.warning("world: loadQuestProgress failed: " + e.getMessage());
        }
        return out;
    }

    public Set<UUID> loadQuestRewarded() {
        Set<UUID> out = new HashSet<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT player_rid FROM world.quest_rewarded");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(rs.getObject(1, UUID.class));
        } catch (SQLException e) {
            log.warning("world: loadQuestRewarded failed: " + e.getMessage());
        }
        return out;
    }

    /** Batch-upsert the whole in-memory quest state in one transaction (called off the main thread). */
    public void saveQuests(Map<UUID, Map<String, Integer>> progress, Set<UUID> rewarded) {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement up = c.prepareStatement(
                    "INSERT INTO world.quest_progress (player_rid, quest_key, amount) VALUES (?, ?, ?) "
                    + "ON CONFLICT (player_rid, quest_key) DO UPDATE SET amount = EXCLUDED.amount")) {
                for (var e : progress.entrySet()) {
                    for (var qe : e.getValue().entrySet()) {
                        up.setObject(1, e.getKey());
                        up.setString(2, qe.getKey());
                        up.setInt(3, qe.getValue());
                        up.addBatch();
                    }
                }
                up.executeBatch();
            }
            try (PreparedStatement rw = c.prepareStatement(
                    "INSERT INTO world.quest_rewarded (player_rid) VALUES (?) ON CONFLICT DO NOTHING")) {
                for (UUID id : rewarded) { rw.setObject(1, id); rw.addBatch(); }
                rw.executeBatch();
            }
            c.commit();
        } catch (SQLException e) {
            log.warning("world: saveQuests failed: " + e.getMessage());
        }
    }

    // ---- server rights (owners / admins) -------------------------------------------------
    // Replaces rights.yml. Entries are name-or-UUID strings, matched case-insensitively.

    public Set<String> loadRights(String role) {
        Set<String> out = new LinkedHashSet<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT identifier FROM world.server_rights WHERE role = ? ORDER BY identifier")) {
            ps.setString(1, role);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        } catch (SQLException e) {
            log.warning("world: loadRights(" + role + ") failed: " + e.getMessage());
        }
        return out;
    }

    public void addRight(String role, String identifier) {
        // Case-insensitive guard so 'Steve' and 'steve' don't both land.
        String sql = "INSERT INTO world.server_rights (role, identifier) SELECT ?, ? "
                + "WHERE NOT EXISTS (SELECT 1 FROM world.server_rights WHERE role = ? AND LOWER(identifier) = LOWER(?))";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, role);
            ps.setString(2, identifier);
            ps.setString(3, role);
            ps.setString(4, identifier);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warning("world: addRight(" + role + ", " + identifier + ") failed: " + e.getMessage());
        }
    }

    public void removeRight(String role, String identifier) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM world.server_rights WHERE role = ? AND LOWER(identifier) = LOWER(?)")) {
            ps.setString(1, role);
            ps.setString(2, identifier);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warning("world: removeRight(" + role + ", " + identifier + ") failed: " + e.getMessage());
        }
    }

    // ---- alliances (+ members, join-requests) --------------------------------------------
    // Replaces alliances.yml. Mutations are rare (social commands) so callers invoke these
    // synchronously, which also keeps create→join ordering simple. One alliance = one row;
    // members/requests reference it by stable id with ON DELETE CASCADE.

    public record AllianceRow(long id, String name, String color, UUID leader,
                              long createdAtMillis, List<UUID> members, List<UUID> pending) {}

    public List<AllianceRow> loadAlliances() {
        // Pull the three tables, then assemble in memory (few alliances → no N+1 concern).
        Map<Long, List<UUID>> members = new HashMap<>();
        Map<Long, List<UUID>> pending = new HashMap<>();
        List<AllianceRow> out = new ArrayList<>();
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT alliance_id, member_rid FROM world.alliance_members");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) members.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(rs.getObject(2, UUID.class));
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT alliance_id, applicant_rid FROM world.alliance_requests");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) pending.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(rs.getObject(2, UUID.class));
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, name, color, leader_rid, created_at_millis FROM world.alliances");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong(1);
                    out.add(new AllianceRow(id, rs.getString(2), rs.getString(3), rs.getObject(4, UUID.class),
                            rs.getLong(5),
                            members.getOrDefault(id, List.of()),
                            pending.getOrDefault(id, List.of())));
                }
            }
        } catch (SQLException e) {
            log.warning("world: loadAlliances failed: " + e.getMessage());
        }
        return out;
    }

    /** Insert the alliance + its leader as the first member, in one txn. Returns the new id (0 on failure). */
    public long insertAlliance(String name, String nameLower, String color, UUID leader, long createdAtMillis) {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            long id;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO world.alliances (name, name_lower, color, leader_rid, created_at_millis) "
                    + "VALUES (?, ?, ?, ?, ?) RETURNING id")) {
                ps.setString(1, name);
                ps.setString(2, nameLower);
                ps.setString(3, color);
                ps.setObject(4, leader);
                ps.setLong(5, createdAtMillis);
                try (ResultSet rs = ps.executeQuery()) { rs.next(); id = rs.getLong(1); }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO world.alliance_members (alliance_id, member_rid) VALUES (?, ?)")) {
                ps.setLong(1, id);
                ps.setObject(2, leader);
                ps.executeUpdate();
            }
            c.commit();
            return id;
        } catch (SQLException e) {
            log.warning("world: insertAlliance(" + name + ") failed: " + e.getMessage());
            return 0L;
        }
    }

    public void deleteAlliance(long id) { run("DELETE FROM world.alliances WHERE id = ?", id); }

    public void renameAlliance(long id, String name, String nameLower) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE world.alliances SET name = ?, name_lower = ? WHERE id = ?")) {
            ps.setString(1, name); ps.setString(2, nameLower); ps.setLong(3, id);
            ps.executeUpdate();
        } catch (SQLException e) { log.warning("world: renameAlliance failed: " + e.getMessage()); }
    }

    public void setAllianceColor(long id, String color) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE world.alliances SET color = ? WHERE id = ?")) {
            ps.setString(1, color); ps.setLong(2, id);
            ps.executeUpdate();
        } catch (SQLException e) { log.warning("world: setAllianceColor failed: " + e.getMessage()); }
    }

    public void addAllianceMember(long id, UUID member)    { link("alliance_members", "member_rid", id, member, true); }
    public void removeAllianceMember(long id, UUID member) { link("alliance_members", "member_rid", id, member, false); }
    public void addAllianceRequest(long id, UUID applicant)    { link("alliance_requests", "applicant_rid", id, applicant, true); }
    public void removeAllianceRequest(long id, UUID applicant) { link("alliance_requests", "applicant_rid", id, applicant, false); }

    private void link(String table, String col, long allianceId, UUID who, boolean add) {
        String sql = add
                ? "INSERT INTO world." + table + " (alliance_id, " + col + ") VALUES (?, ?) ON CONFLICT DO NOTHING"
                : "DELETE FROM world." + table + " WHERE alliance_id = ? AND " + col + " = ?";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, allianceId);
            ps.setObject(2, who);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warning("world: " + (add ? "add" : "remove") + " " + table + " failed: " + e.getMessage());
        }
    }

    private void run(String sql, long id) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warning("world: statement failed [" + sql + "]: " + e.getMessage());
        }
    }

    // ---- claims + turrets + friends ------------------------------------------------------
    // Replaces claims.yml. The in-memory ClaimManager is authoritative; saveClaims() mirrors the
    // whole (small) claim set to the DB in one transaction, called off the main thread.

    public record TurretRow(int slot, int x, int y, int z, int npcId, int structureHp, long respawnAtMillis) {}
    public record ClaimRow(UUID owner, UUID world, int minX, int minZ, int maxX, int maxZ, boolean attackMobs,
                           int[] levels, long[] slotRespawn, List<TurretRow> turrets, List<UUID> friends) {}

    public List<ClaimRow> loadClaims() {
        Map<UUID, int[]> levels = new HashMap<>();
        Map<UUID, long[]> respawns = new HashMap<>();
        Map<UUID, List<TurretRow>> turrets = new HashMap<>();
        Map<UUID, List<UUID>> friends = new HashMap<>();
        List<ClaimRow> out = new ArrayList<>();
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT owner_rid, slot, level, respawn_at_millis FROM world.claim_slots");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID o = rs.getObject(1, UUID.class);
                    int slot = rs.getInt(2);
                    if (slot < 0 || slot > 3) continue;
                    levels.computeIfAbsent(o, k -> new int[]{1, 1, 1, 1})[slot] = rs.getInt(3);
                    respawns.computeIfAbsent(o, k -> new long[4])[slot] = rs.getLong(4);
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT owner_rid, slot, x, y, z, npc_id, structure_hp, respawn_at_millis FROM world.claim_turrets");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    turrets.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>())
                           .add(new TurretRow(rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5),
                                   rs.getInt(6), rs.getInt(7), rs.getLong(8)));
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT owner_rid, friend_rid FROM world.claim_friends");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) friends.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>()).add(rs.getObject(2, UUID.class));
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT owner_rid, world_uuid, min_x, min_z, max_x, max_z, attack_hostile_mobs FROM world.claims");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID o = rs.getObject(1, UUID.class);
                    out.add(new ClaimRow(o, rs.getObject(2, UUID.class), rs.getInt(3), rs.getInt(4), rs.getInt(5), rs.getInt(6),
                            rs.getBoolean(7),
                            levels.getOrDefault(o, new int[]{1, 1, 1, 1}),
                            respawns.getOrDefault(o, new long[4]),
                            turrets.getOrDefault(o, List.of()),
                            friends.getOrDefault(o, List.of())));
                }
            }
        } catch (SQLException e) {
            log.warning("world: loadClaims failed: " + e.getMessage());
        }
        return out;
    }

    public Map<UUID, int[]> loadSavedSlotLevels() {
        Map<UUID, int[]> out = new HashMap<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT owner_rid, slot, level FROM world.saved_slot_levels");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                int slot = rs.getInt(2);
                if (slot < 0 || slot > 3) continue;
                out.computeIfAbsent(rs.getObject(1, UUID.class), k -> new int[]{1, 1, 1, 1})[slot] = rs.getInt(3);
            }
        } catch (SQLException e) {
            log.warning("world: loadSavedSlotLevels failed: " + e.getMessage());
        }
        return out;
    }

    public Map<UUID, String> loadPlayerNames() {
        Map<UUID, String> out = new HashMap<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT rid, name FROM world.player_names");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.put(rs.getObject(1, UUID.class), rs.getString(2));
        } catch (SQLException e) {
            log.warning("world: loadPlayerNames failed: " + e.getMessage());
        }
        return out;
    }

    public Map<UUID, String> loadPlayerGamemodes() {
        Map<UUID, String> out = new HashMap<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT rid, gamemode FROM world.player_gamemodes");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.put(rs.getObject(1, UUID.class), rs.getString(2));
        } catch (SQLException e) {
            log.warning("world: loadPlayerGamemodes failed: " + e.getMessage());
        }
        return out;
    }

    /** Mirror the full claim set + saved-slot-levels to the DB in one transaction (off the main thread). */
    public void saveClaims(List<ClaimRow> claims, Map<UUID, int[]> savedSlotLevels) {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            // Remove claims no longer present (cascade drops their slots/turrets/friends).
            try (PreparedStatement ps = c.prepareStatement("SELECT owner_rid FROM world.claims")) {
                List<UUID> existing = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) { while (rs.next()) existing.add(rs.getObject(1, UUID.class)); }
                java.util.Set<UUID> keep = new HashSet<>();
                for (ClaimRow r : claims) keep.add(r.owner());
                try (PreparedStatement del = c.prepareStatement("DELETE FROM world.claims WHERE owner_rid = ?")) {
                    for (UUID o : existing) if (!keep.contains(o)) { del.setObject(1, o); del.addBatch(); }
                    del.executeBatch();
                }
            }
            for (ClaimRow r : claims) {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO world.claims (owner_rid, world_uuid, min_x, min_z, max_x, max_z, attack_hostile_mobs) "
                        + "VALUES (?,?,?,?,?,?,?) ON CONFLICT (owner_rid) DO UPDATE SET "
                        + "world_uuid=EXCLUDED.world_uuid, min_x=EXCLUDED.min_x, min_z=EXCLUDED.min_z, "
                        + "max_x=EXCLUDED.max_x, max_z=EXCLUDED.max_z, attack_hostile_mobs=EXCLUDED.attack_hostile_mobs")) {
                    ps.setObject(1, r.owner()); ps.setObject(2, r.world());
                    ps.setInt(3, r.minX()); ps.setInt(4, r.minZ()); ps.setInt(5, r.maxX()); ps.setInt(6, r.maxZ());
                    ps.setBoolean(7, r.attackMobs());
                    ps.executeUpdate();
                }
                // children: simplest correct mirror is delete-then-insert per claim (tiny per claim).
                runOwner(c, "DELETE FROM world.claim_slots WHERE owner_rid = ?", r.owner());
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO world.claim_slots (owner_rid, slot, level, respawn_at_millis) VALUES (?,?,?,?)")) {
                    for (int s = 0; s < 4; s++) {
                        ps.setObject(1, r.owner()); ps.setInt(2, s);
                        ps.setInt(3, r.levels().length > s ? r.levels()[s] : 1);
                        ps.setLong(4, r.slotRespawn().length > s ? r.slotRespawn()[s] : 0L);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                runOwner(c, "DELETE FROM world.claim_turrets WHERE owner_rid = ?", r.owner());
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO world.claim_turrets (owner_rid, slot, x, y, z, npc_id, structure_hp, respawn_at_millis) VALUES (?,?,?,?,?,?,?,?)")) {
                    for (TurretRow t : r.turrets()) {
                        ps.setObject(1, r.owner()); ps.setInt(2, t.slot());
                        ps.setInt(3, t.x()); ps.setInt(4, t.y()); ps.setInt(5, t.z());
                        ps.setInt(6, t.npcId()); ps.setInt(7, t.structureHp()); ps.setLong(8, t.respawnAtMillis());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                runOwner(c, "DELETE FROM world.claim_friends WHERE owner_rid = ?", r.owner());
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO world.claim_friends (owner_rid, friend_rid) VALUES (?,?) ON CONFLICT DO NOTHING")) {
                    for (UUID f : r.friends()) { ps.setObject(1, r.owner()); ps.setObject(2, f); ps.addBatch(); }
                    ps.executeBatch();
                }
            }
            // saved-slot-levels: full replace.
            try (PreparedStatement del = c.prepareStatement("DELETE FROM world.saved_slot_levels")) { del.executeUpdate(); }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO world.saved_slot_levels (owner_rid, slot, level) VALUES (?,?,?)")) {
                for (var e : savedSlotLevels.entrySet()) {
                    int[] arr = e.getValue();
                    for (int s = 0; s < 4 && s < arr.length; s++) { ps.setObject(1, e.getKey()); ps.setInt(2, s); ps.setInt(3, arr[s]); ps.addBatch(); }
                }
                ps.executeBatch();
            }
            c.commit();
        } catch (SQLException e) {
            log.warning("world: saveClaims failed: " + e.getMessage());
        }
    }

    private void runOwner(Connection c, String sql, UUID owner) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) { ps.setObject(1, owner); ps.executeUpdate(); }
    }

    // ---- player name cache + saved gamemode (per-change, hot paths) ----------------------

    public void upsertName(UUID rid, String name) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO world.player_names (rid, name) VALUES (?,?) ON CONFLICT (rid) DO UPDATE SET name = EXCLUDED.name")) {
            ps.setObject(1, rid); ps.setString(2, name);
            ps.executeUpdate();
        } catch (SQLException e) { log.warning("world: upsertName failed: " + e.getMessage()); }
    }

    public void upsertGamemode(UUID rid, String gamemode) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO world.player_gamemodes (rid, gamemode) VALUES (?,?) ON CONFLICT (rid) DO UPDATE SET gamemode = EXCLUDED.gamemode")) {
            ps.setObject(1, rid); ps.setString(2, gamemode);
            ps.executeUpdate();
        } catch (SQLException e) { log.warning("world: upsertGamemode failed: " + e.getMessage()); }
    }

    public void deleteGamemode(UUID rid) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "DELETE FROM world.player_gamemodes WHERE rid = ?")) {
            ps.setObject(1, rid);
            ps.executeUpdate();
        } catch (SQLException e) { log.warning("world: deleteGamemode failed: " + e.getMessage()); }
    }

    // ---- raids: cooldowns + per-player loot stash ----------------------------------------

    public Map<UUID, Long> loadRaidCooldowns() {
        Map<UUID, Long> out = new HashMap<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT zone_owner_rid, until_millis FROM world.raid_cooldowns");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.put(rs.getObject(1, UUID.class), rs.getLong(2));
        } catch (SQLException e) { log.warning("world: loadRaidCooldowns failed: " + e.getMessage()); }
        return out;
    }

    /** Full replace of the cooldown set (called off the main thread; the set is tiny). */
    public void saveRaidCooldowns(Map<UUID, Long> cooldowns) {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement del = c.prepareStatement("DELETE FROM world.raid_cooldowns")) { del.executeUpdate(); }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO world.raid_cooldowns (zone_owner_rid, until_millis) VALUES (?,?)")) {
                long now = System.currentTimeMillis();
                for (var e : cooldowns.entrySet()) {
                    if (e.getValue() <= now) continue;   // don't persist expired
                    ps.setObject(1, e.getKey()); ps.setLong(2, e.getValue()); ps.addBatch();
                }
                ps.executeBatch();
            }
            c.commit();
        } catch (SQLException e) { log.warning("world: saveRaidCooldowns failed: " + e.getMessage()); }
    }

    public Map<UUID, byte[]> loadRaidInventoryBlobs() {
        Map<UUID, byte[]> out = new HashMap<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT owner_rid, contents FROM world.raid_inventories");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.put(rs.getObject(1, UUID.class), rs.getBytes(2));
        } catch (SQLException e) { log.warning("world: loadRaidInventoryBlobs failed: " + e.getMessage()); }
        return out;
    }

    /** Full replace of the loot stashes. Blobs are serialized by the caller on the main thread. */
    public void saveRaidInventoryBlobs(Map<UUID, byte[]> blobs) {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement del = c.prepareStatement("DELETE FROM world.raid_inventories")) { del.executeUpdate(); }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO world.raid_inventories (owner_rid, contents) VALUES (?,?)")) {
                for (var e : blobs.entrySet()) {
                    if (e.getValue() == null) continue;
                    ps.setObject(1, e.getKey()); ps.setBytes(2, e.getValue()); ps.addBatch();
                }
                ps.executeBatch();
            }
            c.commit();
        } catch (SQLException e) { log.warning("world: saveRaidInventoryBlobs failed: " + e.getMessage()); }
    }

    // ---- tactical leaves (combat-log punishment) ------------------------------------------
    // One row per player who quit low-HP while chased. Written on quit (with an inventory
    // snapshot); executed at the 10-min expiry (items dropped, executed=TRUE, snapshot nulled);
    // finally consumed at the victim's next /login (inventory wipe). A /login within the grace
    // deletes the row (pardon).

    public record TacticalLeaveRow(UUID victimRid, String attackerName, UUID worldUuid,
                                   double x, double y, double z, long quitAtMillis,
                                   byte[] inventory, boolean executed) {}

    public void upsertTacticalLeave(TacticalLeaveRow row) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement("""
                INSERT INTO world.tactical_leaves (victim_rid, attacker_name, world_uuid, x, y, z, quit_at_millis, inventory, executed)
                VALUES (?,?,?,?,?,?,?,?,?)
                ON CONFLICT (victim_rid) DO UPDATE SET attacker_name = EXCLUDED.attacker_name,
                    world_uuid = EXCLUDED.world_uuid, x = EXCLUDED.x, y = EXCLUDED.y, z = EXCLUDED.z,
                    quit_at_millis = EXCLUDED.quit_at_millis, inventory = EXCLUDED.inventory,
                    executed = EXCLUDED.executed
                """)) {
            ps.setObject(1, row.victimRid());
            ps.setString(2, row.attackerName());
            ps.setObject(3, row.worldUuid());
            ps.setDouble(4, row.x()); ps.setDouble(5, row.y()); ps.setDouble(6, row.z());
            ps.setLong(7, row.quitAtMillis());
            ps.setBytes(8, row.inventory());
            ps.setBoolean(9, row.executed());
            ps.executeUpdate();
        } catch (SQLException e) { log.warning("world: upsertTacticalLeave failed for " + row.victimRid() + ": " + e.getMessage()); }
    }

    /** The pending tactical-leave row for a player, or null. */
    public TacticalLeaveRow loadTacticalLeave(UUID victimRid) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT attacker_name, world_uuid, x, y, z, quit_at_millis, inventory, executed"
                        + " FROM world.tactical_leaves WHERE victim_rid = ?")) {
            ps.setObject(1, victimRid);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new TacticalLeaveRow(victimRid, rs.getString(1), rs.getObject(2, UUID.class),
                        rs.getDouble(3), rs.getDouble(4), rs.getDouble(5), rs.getLong(6),
                        rs.getBytes(7), rs.getBoolean(8));
            }
        } catch (SQLException e) {
            log.warning("world: loadTacticalLeave failed for " + victimRid + ": " + e.getMessage());
            return null;
        }
    }

    /** All rows — used on boot to re-arm expiry timers lost to the restart. */
    public List<TacticalLeaveRow> loadAllTacticalLeaves() {
        List<TacticalLeaveRow> out = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "SELECT victim_rid, attacker_name, world_uuid, x, y, z, quit_at_millis, inventory, executed"
                        + " FROM world.tactical_leaves");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new TacticalLeaveRow(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getObject(3, UUID.class), rs.getDouble(4), rs.getDouble(5), rs.getDouble(6),
                        rs.getLong(7), rs.getBytes(8), rs.getBoolean(9)));
            }
        } catch (SQLException e) { log.warning("world: loadAllTacticalLeaves failed: " + e.getMessage()); }
        return out;
    }

    /** Items dropped: flag the row for the next-login inventory wipe and drop the snapshot blob. */
    public void markTacticalLeaveExecuted(UUID victimRid) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "UPDATE world.tactical_leaves SET executed = TRUE, inventory = NULL WHERE victim_rid = ?")) {
            ps.setObject(1, victimRid);
            ps.executeUpdate();
        } catch (SQLException e) { log.warning("world: markTacticalLeaveExecuted failed for " + victimRid + ": " + e.getMessage()); }
    }

    public void deleteTacticalLeave(UUID victimRid) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "DELETE FROM world.tactical_leaves WHERE victim_rid = ?")) {
            ps.setObject(1, victimRid);
            ps.executeUpdate();
        } catch (SQLException e) { log.warning("world: deleteTacticalLeave failed for " + victimRid + ": " + e.getMessage()); }
    }

    // ---- bans (unified into public.account_flags 'mc_ban') -------------------------------
    // Writes the auth eligibility table directly via the shared pool, so a ban enforces through
    // the same gate as everything else. account_rid FK requires the player to have an account
    // (they do — the gate requires registration to join).

    public boolean banAccount(UUID accountUuid, String reason, java.time.Instant expiresAt) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO public.account_flags (account_rid, source, reason, expires_at) VALUES (?, 'mc_ban', ?, ?)")) {
            ps.setObject(1, accountUuid);
            ps.setString(2, reason);
            if (expiresAt != null) ps.setObject(3, java.time.OffsetDateTime.ofInstant(expiresAt, java.time.ZoneOffset.UTC));
            else ps.setNull(3, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            log.warning("world: banAccount(" + accountUuid + ") failed (no account?): " + e.getMessage());
            return false;
        }
    }

    /** Clear in-game bans for an account. Returns rows removed (0 = wasn't banned). */
    public int unbanAccount(UUID accountUuid) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "DELETE FROM public.account_flags WHERE account_rid = ? AND source = 'mc_ban'")) {
            ps.setObject(1, accountUuid);
            return ps.executeUpdate();
        } catch (SQLException e) {
            log.warning("world: unbanAccount(" + accountUuid + ") failed: " + e.getMessage());
            return 0;
        }
    }

    /** UUIDs with an active in-game ban (for /unban tab-completion). */
    public List<UUID> activelyBannedUuids() {
        List<UUID> out = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "SELECT DISTINCT account_rid FROM public.account_flags WHERE source = 'mc_ban' AND (expires_at IS NULL OR expires_at > NOW())");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(rs.getObject(1, UUID.class));
        } catch (SQLException e) { log.warning("world: activelyBannedUuids failed: " + e.getMessage()); }
        return out;
    }
}
