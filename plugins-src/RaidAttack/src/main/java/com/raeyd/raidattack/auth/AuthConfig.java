package com.raeyd.raidattack.auth;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Login-gate settings, read from the {@code auth:} section of config.yml. The DB password lives
 * only in the runtime config (server/plugins/RaidAttack/config.yml, which is gitignored) — the
 * bundled resource ships a placeholder.
 */
public final class AuthConfig {

    public final boolean enabled;
    /** Whether Bedrock (Floodgate) players may log in. Until the Floodgate link provider gives them
     *  the canonical Java UUID, a Bedrock connection cannot be unified/auth-gated correctly, so this
     *  stays OFF (Bedrock denied at pre-login) to avoid bypassing the password gate. */
    public final boolean crossplayEnabled;
    public final int limboSeconds;
    public final int maxAttempts;
    public final int windowMinutes;
    public final int lockoutMinutes;

    public final String jdbcUrl;
    public final String user;
    public final String password;
    public final int poolSize;

    public AuthConfig(ConfigurationSection s) {
        if (s == null) {
            // Hard default: gate ON but with no DB — the gate then fails closed until configured.
            this.enabled = false;
            this.crossplayEnabled = false;
            this.limboSeconds = 30;
            this.maxAttempts = 5;
            this.windowMinutes = 60;
            this.lockoutMinutes = 60;
            this.jdbcUrl = "jdbc:postgresql://127.0.0.1:5432/raidattack";
            this.user = "ra_plugin";
            this.password = "";
            this.poolSize = 4;
            return;
        }
        this.enabled = s.getBoolean("enabled", true);
        this.crossplayEnabled = s.getBoolean("crossplay_enabled", false);
        this.limboSeconds = Math.max(5, s.getInt("limbo_seconds", 30));
        this.maxAttempts = Math.max(1, s.getInt("max_attempts", 5));
        this.windowMinutes = Math.max(1, s.getInt("window_minutes", 60));
        this.lockoutMinutes = Math.max(1, s.getInt("lockout_minutes", 60));

        ConfigurationSection db = s.getConfigurationSection("database");
        String host = db != null ? db.getString("host", "127.0.0.1") : "127.0.0.1";
        int port = db != null ? db.getInt("port", 5432) : 5432;
        String database = db != null ? db.getString("database", "raidattack") : "raidattack";
        this.user = db != null ? db.getString("user", "ra_plugin") : "ra_plugin";
        this.password = db != null ? db.getString("password", "") : "";
        this.poolSize = db != null ? Math.max(1, db.getInt("pool_size", 4)) : 4;
        this.jdbcUrl = "jdbc:postgresql://" + host + ":" + port + "/" + database;
    }
}
