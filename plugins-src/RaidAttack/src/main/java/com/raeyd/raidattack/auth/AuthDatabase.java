package com.raeyd.raidattack.auth;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * All access to the separate {@code raidattack} Postgres database, as the least-privilege
 * {@code ra_plugin} role. Every method here BLOCKS and must only be called off the main server
 * thread (AsyncPlayerPreLoginEvent runs async; everything else is dispatched to the scheduler's
 * async pool). The bot owns the schema; the plugin only reads accounts, verifies, and writes the
 * narrow set of auth columns + the lockout flag + the attempt log.
 */
public final class AuthDatabase {

    /** Minimal account view the gate needs. {@code passwordHash} is verified in-memory and never logged. */
    public record AccountAuth(UUID rid, String username, String approval, String passwordHash) {}

    /** Result of counting a failed attempt: the running count and whether it just triggered a lockout. */
    public record LockResult(int count, boolean lockedNow) {}

    private final HikariDataSource ds;

    /** Uses the plugin's shared connection pool (see {@code data.RaidAttackPool}); the pool's
     *  lifecycle is owned by the plugin, not this class. */
    public AuthDatabase(HikariDataSource sharedPool) {
        this.ds = sharedPool;
    }

    /** Cheap liveness probe used once at enable to log a clear warning if the DB is unreachable. */
    public boolean ping() {
        try (Connection c = ds.getConnection()) {
            return c.isValid(3);
        } catch (SQLException e) {
            return false;
        }
    }

    public AccountAuth findByUsernameLower(String usernameLower) {
        return findBy("username_lower", usernameLower, "findByUsernameLower");
    }

    /** Resolve a Bedrock player already bound to their account (by the Floodgate UUID). */
    public AccountAuth findByBedrockUuid(UUID bedrockUuid) {
        String sql = "SELECT rid, username, approval, password_hash FROM accounts WHERE bedrock_uuid = ?";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, bedrockUuid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readAuth(rs) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("findByBedrockUuid failed", e);
        }
    }

    /** Resolve by the registered Bedrock gamertag (case-insensitive) — used on first Bedrock login. */
    public AccountAuth findByBedrockUsernameLower(String gamertagLower) {
        return findBy("bedrock_username_lower", gamertagLower, "findByBedrockUsernameLower");
    }

    /** Bind a Bedrock player's Floodgate UUID to their account on first login (only if unbound). */
    public void bindBedrockUuid(UUID canonicalUuid, UUID bedrockUuid) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "UPDATE accounts SET bedrock_uuid = ? WHERE rid = ? AND bedrock_uuid IS NULL")) {
            ps.setObject(1, bedrockUuid);
            ps.setObject(2, canonicalUuid);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("bindBedrockUuid failed", e);
        }
    }

    private AccountAuth findBy(String column, String value, String op) {
        String sql = "SELECT rid, username, approval, password_hash FROM accounts WHERE " + column + " = ?";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readAuth(rs) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException(op + " failed", e);
        }
    }

    private static AccountAuth readAuth(ResultSet rs) throws SQLException {
        return new AccountAuth(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4));
    }

    public List<String> activeFlagSources(UUID uuid) {
        String sql = "SELECT DISTINCT source FROM account_flags WHERE account_rid = ? AND (expires_at IS NULL OR expires_at > NOW())";
        List<String> out = new ArrayList<>();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new RuntimeException("activeFlagSources failed", e);
        }
        return out;
    }

    /** When the active login lockout expires (latest one), if any — used for the "locked Xm" message. */
    public Optional<Instant> lockoutExpiry(UUID uuid) {
        String sql = "SELECT MAX(expires_at) FROM account_flags WHERE account_rid = ? AND source = 'login_lockout' AND expires_at > NOW()";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Timestamp ts = rs.getTimestamp(1);
                    if (ts != null) return Optional.of(ts.toInstant());
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("lockoutExpiry failed", e);
        }
        return Optional.empty();
    }

    /** Reason + (optional) expiry of the active in-game ban, if any. */
    public record BanInfo(String reason, Optional<Instant> expires) {}

    public Optional<BanInfo> activeBan(UUID uuid) {
        String sql = "SELECT reason, expires_at FROM account_flags "
                + "WHERE account_rid = ? AND source = 'mc_ban' AND (expires_at IS NULL OR expires_at > NOW()) "
                + "ORDER BY expires_at DESC NULLS FIRST LIMIT 1";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Timestamp ts = rs.getTimestamp(2);
                    return Optional.of(new BanInfo(rs.getString(1) == null ? "" : rs.getString(1),
                            ts == null ? Optional.empty() : Optional.of(ts.toInstant())));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("activeBan failed", e);
        }
        return Optional.empty();
    }

    public void recordAttempt(UUID accountUuid, String username, String ip, String outcome) {
        String sql = "INSERT INTO login_attempts (account_rid, username, ip, outcome) VALUES (?, ?, ?, ?)";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, accountUuid);
            ps.setString(2, username);
            ps.setString(3, ip);
            ps.setString(4, outcome);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("recordAttempt failed", e);
        }
    }

    public void markSuccess(UUID uuid, String ip) {
        String sql = "UPDATE accounts SET last_login_at = NOW(), last_login_ip = ?, failed_attempts = 0, failed_window_started_at = NULL WHERE rid = ?";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, ip);
            ps.setObject(2, uuid);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("markSuccess failed", e);
        }
    }

    /**
     * Count one failed attempt against a rolling window and, if it reaches the threshold, write a
     * {@code login_lockout} flag (expiring after {@code lockoutMinutes}) and reset the counter.
     * Done in one transaction with a row lock so concurrent joins on the same account can't race
     * the counter. A timeout-kick calls this too — a no-show still counts as an attempt.
     */
    public LockResult bumpFailedAndMaybeLock(UUID uuid, int maxAttempts, int windowMinutes, int lockoutMinutes) {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                int count;
                Timestamp windowStart;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT failed_attempts, failed_window_started_at FROM accounts WHERE rid = ? FOR UPDATE")) {
                    ps.setObject(1, uuid);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) { c.rollback(); return new LockResult(0, false); }
                        count = rs.getInt(1);
                        windowStart = rs.getTimestamp(2);
                    }
                }

                Instant now = Instant.now();
                boolean stale = windowStart == null
                        || now.isAfter(windowStart.toInstant().plus(Duration.ofMinutes(windowMinutes)));
                int newCount = stale ? 1 : count + 1;
                Instant newWindowStart = stale ? now : windowStart.toInstant();
                boolean lockNow = newCount >= maxAttempts;

                if (lockNow) {
                    try (PreparedStatement ins = c.prepareStatement(
                            "INSERT INTO account_flags (account_rid, source, reason, expires_at) VALUES (?, 'login_lockout', ?, ?)")) {
                        ins.setObject(1, uuid);
                        ins.setString(2, maxAttempts + " failed logins within " + windowMinutes + " min");
                        ins.setTimestamp(3, Timestamp.from(now.plus(Duration.ofMinutes(lockoutMinutes))));
                        ins.executeUpdate();
                    }
                    try (PreparedStatement upd = c.prepareStatement(
                            "UPDATE accounts SET failed_attempts = 0, failed_window_started_at = NULL WHERE rid = ?")) {
                        upd.setObject(1, uuid);
                        upd.executeUpdate();
                    }
                } else {
                    try (PreparedStatement upd = c.prepareStatement(
                            "UPDATE accounts SET failed_attempts = ?, failed_window_started_at = ? WHERE rid = ?")) {
                        upd.setInt(1, newCount);
                        upd.setTimestamp(2, Timestamp.from(newWindowStart));
                        upd.setObject(3, uuid);
                        upd.executeUpdate();
                    }
                }
                c.commit();
                return new LockResult(newCount, lockNow);
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new RuntimeException("bumpFailedAndMaybeLock failed", e);
        }
    }
}
