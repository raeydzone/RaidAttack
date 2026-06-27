package com.raeyd.raidattack.data;

import com.raeyd.raidattack.auth.AuthConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Builds the single Hikari connection pool the plugin uses for BOTH the auth gate (public schema)
 * and gameplay state (world schema). One pool, one role ({@code ra_plugin}), one database
 * ({@code raidattack}) — auth tables are reached unqualified in {@code public}, gameplay tables as
 * {@code world.*}. Credentials come from the {@code auth.database} block in config.yml.
 */
public final class RaidAttackPool {

    private RaidAttackPool() {}

    public static HikariDataSource build(AuthConfig cfg) {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(cfg.jdbcUrl);
        // Paper loads the driver in the plugin's isolated classloader, so name it explicitly
        // (DriverManager auto-discovery can't see it -> "No suitable driver").
        hc.setDriverClassName("org.postgresql.Driver");
        hc.setUsername(cfg.user);
        hc.setPassword(cfg.password);
        hc.setMaximumPoolSize(Math.max(cfg.poolSize, 6));
        hc.setMinimumIdle(1);
        hc.setPoolName("RaidAttackDB");
        hc.setConnectionTimeout(5_000);
        // Don't probe on construct: the plugin must always enable even if the DB is briefly down.
        hc.setInitializationFailTimeout(-1);
        return new HikariDataSource(hc);
    }
}
