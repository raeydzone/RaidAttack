package com.raeyd.raidattack;

import com.raeyd.raidattack.alliance.AllianceCommand;
import com.raeyd.raidattack.alliance.AllianceManager;
import com.raeyd.raidattack.alliance.AlliancePvpListener;
import com.raeyd.raidattack.auth.AuthConfig;
import com.raeyd.raidattack.auth.AuthDatabase;
import com.raeyd.raidattack.auth.AuthGateListener;
import com.raeyd.raidattack.auth.AuthManager;
import com.raeyd.raidattack.auth.LoginCommand;
import com.raeyd.raidattack.data.RaidAttackPool;
import com.raeyd.raidattack.data.WorldDatabase;
import com.raeyd.raidattack.claim.Claim;
import com.raeyd.raidattack.claim.ClaimManager;
import com.raeyd.raidattack.claim.HomeSystemCommand;
import com.raeyd.raidattack.claim.HomeTeleportManager;
import com.raeyd.raidattack.claim.RightsManager;
import com.raeyd.raidattack.claim.SpawnAreaManager;
import com.raeyd.raidattack.claim.ZoneListener;
import com.raeyd.raidattack.combat.ArmorDurabilityManager;
import com.raeyd.raidattack.combat.CombatBalanceListener;
import com.raeyd.raidattack.combat.DamageTrackingListener;
import com.raeyd.raidattack.combat.EndPhantomManager;
import com.raeyd.raidattack.combat.PvpModeManager;
import com.raeyd.raidattack.combat.TacticalLeaveManager;
import com.raeyd.raidattack.combat.HappyGhastManager;
import com.raeyd.raidattack.combat.WitherCombatManager;
import com.raeyd.raidattack.core.ChatListener;
import com.raeyd.raidattack.core.CitizensAuditor;
import com.raeyd.raidattack.core.ComputePool;
import com.raeyd.raidattack.core.HoverWatchdog;
import com.raeyd.raidattack.core.LagSimulator;
import com.raeyd.raidattack.moderation.ModerationCommand;
import com.raeyd.raidattack.moderation.ModerationListener;
import com.raeyd.raidattack.moderation.ModerationService;
import com.raeyd.raidattack.quest.QuestManager;
import com.raeyd.raidattack.raid.ActiveRaid;
import com.raeyd.raidattack.raid.RaidAttackCommand;
import com.raeyd.raidattack.raid.RaidBossBars;
import com.raeyd.raidattack.raid.RaidCommand;
import com.raeyd.raidattack.raid.RaidEntityManager;
import com.raeyd.raidattack.raid.RaidLootManager;
import com.raeyd.raidattack.raid.RaidManager;
import com.raeyd.raidattack.raid.RaidSpawnEngine;
import com.raeyd.raidattack.raid.RaiderDamageListener;
import com.raeyd.raidattack.season.EventCommand;
import com.raeyd.raidattack.season.EventManager;
import com.raeyd.raidattack.season.SeasonGateListener;
import com.raeyd.raidattack.turret.Turret;
import com.raeyd.raidattack.turret.TurretBuffManager;
import com.raeyd.raidattack.turret.TurretBulletListener;
import com.raeyd.raidattack.turret.TurretBulletRemoveListener;
import com.raeyd.raidattack.turret.TurretChunkListener;
import com.raeyd.raidattack.turret.TurretCombatManager;
import com.raeyd.raidattack.turret.TurretEnemyTracker;
import com.raeyd.raidattack.turret.TurretEntityManager;
import com.raeyd.raidattack.turret.TurretKillListener;
import com.raeyd.raidattack.turret.TurretPlacementQueue;
import com.raeyd.raidattack.turret.TurretProtectionListener;
import com.raeyd.raidattack.turret.TurretStructure;
import com.raeyd.raidattack.turret.TurretUpgradeListener;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class HomeSystemPlugin extends JavaPlugin {

    /**
     * Stable, non-resolvable UUID representing the imaginary "alt player" used by the
     * {@code /HomeSystem dev …} prefix. Real players never collide with this UUID; the
     * {@code def} hex suffix is coincidental (the alias is "dev", but {@code v} isn't hex).
     */
    public static final UUID DEV_UUID = UUID.fromString("00000000-0000-0000-0000-000000000def");

    /** Legacy scoreboard team from an attempted red-nameplate pass. Removed on boot. */
    public static final String RAIDERS_TEAM = "raid_raiders";

    private ClaimManager claimManager;
    private ZoneListener zoneListener;
    private LagSimulator lagSimulator;
    private HoverWatchdog hoverWatchdog;
    private TurretPlacementQueue turretQueue;
    private TurretEntityManager turretEntities;
    private TurretCombatManager turretCombat;
    private TurretEnemyTracker turretEnemies;
    private TurretBuffManager turretBuffs;
    private WitherCombatManager witherCombat;
    private AllianceManager allianceManager;
    private RaidEntityManager raidEntities;
    private RaidManager raidManager;
    private RaidBossBars raidBossBars;
    private RaidSpawnEngine raidSpawnEngine;
    private RaidLootManager raidLoot;
    private CitizensAuditor citizensAuditor;
    private RightsManager rightsManager;
    private ModerationService moderationService;
    private ArmorDurabilityManager armorDurability;
    private PvpModeManager pvpMode;
    private TacticalLeaveManager tacticalLeaves;
    private HappyGhastManager happyGhasts;
    private QuestManager quests;
    private HomeTeleportManager homeTeleport;
    private EndPhantomManager endPhantoms;
    private EventManager eventManager;
    private SpawnAreaManager spawnArea;
    private ComputePool computePool;
    private AuthConfig authConfig;
    private com.zaxxer.hikari.HikariDataSource sharedDbPool;
    private WorldDatabase worldDatabase;
    private AuthDatabase authDatabase;
    private AuthManager authManager;

    @Override
    public void onEnable() {
        // First-launch data-folder migration: the plugin used to be called "HomeSystem", and
        // saved everything under plugins/HomeSystem/. Now we're "RaidAttack" — if the new
        // folder is empty / missing and the old one still exists, move the old contents over
        // so claims, names, alliances, etc. don't appear to vanish on the rename.
        migrateLegacyDataFolder();

        // Ensure config.yml exists on disk. saveDefaultConfig() writes the bundled template
        // (with its comments + the events: section) only when the file is absent, so we never strip
        // the user's comments on a later boot. copyDefaults lets any missing key fall back to the
        // bundled default for older configs.
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);

        // Shared DB pool + gameplay (world-schema) persistence. Built early so the gameplay managers
        // below load from it; the same pool also backs the auth gate further down. config.yml is the
        // only file that stays — all gameplay state now lives in the `world` schema of the DB.
        authConfig = new AuthConfig(getConfig().getConfigurationSection("auth"));
        try {
            sharedDbPool = RaidAttackPool.build(authConfig);
            worldDatabase = new WorldDatabase(sharedDbPool, getLogger());
            worldDatabase.ensureSchema();
        } catch (Throwable t) {
            getLogger().severe("RaidAttack: world database init failed — gameplay persistence is degraded: " + t);
        }

        // Worker pool for off-main-thread compute (raider A* replans). Created early so every
        // subsystem can submit to it; shut down in onDisable.
        computePool = new ComputePool();
        getLogger().info("Compute pool started with " + computePool.threadCount() + " worker thread(s).");

        claimManager = new ClaimManager(this, worldDatabase);
        claimManager.load();
        claimManager.start();

        allianceManager = new AllianceManager(this, worldDatabase);
        allianceManager.load();

        zoneListener = new ZoneListener(this);
        getServer().getPluginManager().registerEvents(zoneListener, this);

        // Season event timeline — gates Nether/End/Raiding by fixed UTC dates with no player bypass,
        // drives the /event board, the pre-unlock countdown broadcasts, and the dimension lock.
        eventManager = new EventManager(this);
        eventManager.start();
        getServer().getPluginManager().registerEvents(new SeasonGateListener(this), this);

        // Public spawn area — anti-spawn-camp random respawn + adventure/explosion protection around
        // world spawn. ZoneListener reads plugin.getSpawnArea() for its gamemode + explosion rules,
        // so it must exist before players act (plain listener, no scheduled task to start/stop).
        spawnArea = new SpawnAreaManager(this);
        getServer().getPluginManager().registerEvents(spawnArea, this);

        // World border (config: worldborder-size) — overworld at the configured size, Nether at /8,
        // re-applied on world load. Self-contained; no field needed (the listener registry holds it).
        com.raeyd.raidattack.core.WorldBorderManager worldBorders = new com.raeyd.raidattack.core.WorldBorderManager(this);
        worldBorders.applyAll();
        getServer().getPluginManager().registerEvents(worldBorders, this);

        lagSimulator = new LagSimulator(this);

        hoverWatchdog = new HoverWatchdog(this);
        hoverWatchdog.start();

        turretEntities = new TurretEntityManager(this);
        turretEntities.start();

        turretQueue = new TurretPlacementQueue(this);
        turretQueue.start();

        turretEnemies = new TurretEnemyTracker(this);
        turretEnemies.start();

        turretCombat = new TurretCombatManager(this);
        turretCombat.start();

        turretBuffs = new TurretBuffManager(this);
        turretBuffs.start();

        witherCombat = new WitherCombatManager(this);
        witherCombat.start();
        getServer().getPluginManager().registerEvents(witherCombat, this);

        raidEntities = new RaidEntityManager(this);
        raidEntities.start();

        // Do not put Citizens player-NPCs on a scoreboard team: Paper 26.1 clients can crash
        // on the resulting set_player_team removal packet. Remove any stale team from disk.
        try {
            org.bukkit.scoreboard.Scoreboard sb = getServer().getScoreboardManager().getMainScoreboard();
            org.bukkit.scoreboard.Team team = sb.getTeam(RAIDERS_TEAM);
            if (team != null) team.unregister();
        } catch (Throwable t) {
            getLogger().warning("Failed to unregister legacy raid_raiders scoreboard team: " + t);
        }

        raidManager = new RaidManager(this);
        raidManager.load();
        // Dev mode: every plugin enable wipes any persisted raids so we don't have to wrestle
        // with stale state vs. fresh code. Removed when the system is more mature; production
        // would want graceful resume of partial raids using the saved alive counts.
        if (!raidManager.allActive().isEmpty()) {
            raidManager.wipeAllActiveRaids("dev: wipe on plugin enable");
        }

        raidBossBars = new RaidBossBars(this);
        raidBossBars.start();
        // Register PlayerQuitEvent handler so a viewer who disconnects is dropped from the
        // tracked-viewers set — without that, the rejoin path would skip the show-bar packet
        // because the UUID still appears tracked from before the disconnect.
        getServer().getPluginManager().registerEvents(raidBossBars, this);
        // Re-create bars for any raids that survived the restart so defenders see the boss
        // bar pop back up on rejoin instead of having to wait for the next start tick.
        for (ActiveRaid r : raidManager.allActive()) {
            raidBossBars.onRaidStarted(r);
        }

        // Spawn engine after the boss bars are up — its recovery path may instantly spawn
        // raiders, and we want the bar in place to receive them. The engine reads its own
        // state from the loaded raids.yml so no extra wiring is needed here.
        raidSpawnEngine = new RaidSpawnEngine(this);
        raidSpawnEngine.start();

        // Raid loot system — persistent /raid inventories + the 10s all-turrets-down steal
        // ticker. Needs raidManager + witherCombat (both up by now). start() loads the
        // saved inventories and schedules the ticker; the close listener persists on GUI close.
        raidLoot = new RaidLootManager(this);
        raidLoot.start();
        getServer().getPluginManager().registerEvents(raidLoot, this);

        // Citizens auditor — runs ONE immediate pass to wipe stale NPCs Citizens persisted
        // from prior sessions (anything not in our turret/raid tracking is destroyed), then
        // periodically every 10s. Has to come after the spawn engine recovers so freshly
        // re-spawned raid NPCs aren't seen as orphans on the first sweep.
        citizensAuditor = new CitizensAuditor(this);
        int wipedAtBoot = citizensAuditor.audit();
        if (wipedAtBoot > 0) {
            getLogger().info("Boot audit wiped " + wipedAtBoot + " stale Citizens NPC(s).");
        }
        citizensAuditor.start();

        getServer().getPluginManager().registerEvents(new RaiderDamageListener(this), this);

        RaidCommand raidHandler = new RaidCommand(this);
        PluginCommand raidCmd = getCommand("raid");
        if (raidCmd != null) {
            raidCmd.setExecutor(raidHandler);
            raidCmd.setTabCompleter(raidHandler);
            getServer().getPluginManager().registerEvents(raidHandler, this);
        } else {
            getLogger().severe("Command 'raid' not found in plugin.yml — /raid will not be available.");
        }

        PluginCommand eventCmd = getCommand("event");
        if (eventCmd != null) {
            eventCmd.setExecutor(new EventCommand(this));
        } else {
            getLogger().severe("Command 'event' not found in plugin.yml — /event will not be available.");
        }

        getServer().getPluginManager().registerEvents(new TurretProtectionListener(this), this);
        getServer().getPluginManager().registerEvents(new TurretBulletListener(this), this);
        getServer().getPluginManager().registerEvents(new TurretBulletRemoveListener(this), this);
        getServer().getPluginManager().registerEvents(new TurretUpgradeListener(), this);
        getServer().getPluginManager().registerEvents(new TurretChunkListener(this), this);
        getServer().getPluginManager().registerEvents(new TurretKillListener(this), this);

        getServer().getPluginManager().registerEvents(new ChatListener(this), this);
        getServer().getPluginManager().registerEvents(new AlliancePvpListener(this), this);

        AllianceCommand allianceHandler = new AllianceCommand(this);
        PluginCommand allianceCmd = getCommand("alliance");
        if (allianceCmd != null) { allianceCmd.setExecutor(allianceHandler); allianceCmd.setTabCompleter(allianceHandler); }
        PluginCommand aCmd = getCommand("a");
        if (aCmd != null) { aCmd.setExecutor(allianceHandler); aCmd.setTabCompleter(allianceHandler); }

        HomeSystemCommand handler = new HomeSystemCommand(this);
        PluginCommand cmd = getCommand("homesystem");
        if (cmd == null) {
            getLogger().severe("Command 'homesystem' not found in plugin.yml — aborting.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        cmd.setExecutor(handler);
        cmd.setTabCompleter(handler);

        // Home-teleport channel (/hs teleport) — 15 s channelled warp to a random surface spot in
        // the caller's own claim, broken by movement or damage.
        homeTeleport = new HomeTeleportManager(this);
        homeTeleport.start();
        getServer().getPluginManager().registerEvents(homeTeleport, this);

        // --- /ra umbrella: rights system, moderation routing, armor durability ---
        rightsManager = new RightsManager(this, worldDatabase);
        rightsManager.load();
        rightsManager.ensureAllOnlineOwnersOp();

        moderationService = new ModerationService(this);
        getServer().getPluginManager().registerEvents(new ModerationListener(this), this);
        // Register /ban /kick /unban as our OWN commands (overriding vanilla in the client's command
        // tree) so the client shows our <player> <duration> <reason> syntax and asks the server for
        // tab-completions. ModerationListener still consumes execution; this is for the client UX.
        getLifecycleManager().registerEventHandler(
                io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents.COMMANDS, event -> {
            var registrar = event.registrar();
            registrar.register("ban", "RaidAttack: ban a player (duration + reason)",
                    java.util.List.of(), new ModerationCommand(this, ModerationCommand.Type.BAN));
            registrar.register("kick", "RaidAttack: kick a player (reason)",
                    java.util.List.of(), new ModerationCommand(this, ModerationCommand.Type.KICK));
            registrar.register("unban", "RaidAttack: lift a ban",
                    java.util.List.of("pardon"), new ModerationCommand(this, ModerationCommand.Type.UNBAN));
        });

        armorDurability = new ArmorDurabilityManager(this, worldDatabase);
        armorDurability.load();
        getServer().getPluginManager().registerEvents(armorDurability, this);

        // RaidAttack quests / achievements — progress is poked from the various subsystems.
        quests = new QuestManager(this, worldDatabase);
        quests.load();
        quests.start();

        RaidAttackCommand raHandler = new RaidAttackCommand(this);
        PluginCommand raCmd = getCommand("ra");
        if (raCmd != null) {
            raCmd.setExecutor(raHandler);
            raCmd.setTabCompleter(raHandler);
        } else {
            getLogger().severe("Command 'ra' not found in plugin.yml — /ra will not be available.");
        }

        happyGhasts = new HappyGhastManager(this);
        happyGhasts.start();
        getServer().getPluginManager().registerEvents(happyGhasts, this);

        // Console-only combat damage tracker (balancing data for mace / spear / crystal / anchor).
        getServer().getPluginManager().registerEvents(new DamageTrackingListener(this), this);
        // Active combat nerfs (mace/spear caps + crystal/anchor explosion rework).
        getServer().getPluginManager().registerEvents(new CombatBalanceListener(this), this);

        // PvP mode — pairwise >25-raw-damage / 5-min-window / 250-block tracking. Foundation for
        // the tactical-leaving punishment; must exist before TacticalLeaveManager registers.
        pvpMode = new PvpModeManager(this);
        getServer().getPluginManager().registerEvents(pvpMode, this);
        pvpMode.start();

        // Combat-log ("tactical leaving") punishment — flags anyone who quits while in PvP mode;
        // executes once the 10-min cumulative offline budget is gone (items drop at the quit
        // spot). Rejoining pauses the clock but does NOT pardon — only the end of PvP mode does.
        tacticalLeaves = new TacticalLeaveManager(this, worldDatabase);
        getServer().getPluginManager().registerEvents(tacticalLeaves, this);
        tacticalLeaves.start();   // re-arm offline timers that a restart would otherwise lose

        // Phantoms: removed from the Overworld (insomnia spawn cancelled) and relocated to The End,
        // where a ticker custom-spawns them in the air around players at a hostile-mob-like rate.
        endPhantoms = new EndPhantomManager(this);
        endPhantoms.start();
        getServer().getPluginManager().registerEvents(endPhantoms, this);

        // --- Discord-gated login system (offline-mode auth) ---
        // Accounts are provisioned only via the Discord bot's /raidattack command; the plugin just
        // VALIDATES at login. Eligible players spawn frozen in limbo until /login; everyone else is
        // denied at pre-login. Toggle with auth.enabled in config.yml. Uses the shared DB pool
        // (authConfig was parsed + the pool built early, above).
        if (authConfig != null && authConfig.enabled && sharedDbPool != null) {
            try {
                authDatabase = new AuthDatabase(sharedDbPool);
                if (!authDatabase.ping()) {
                    getLogger().warning("Auth: the raidattack database was unreachable at enable — "
                            + "logins fail closed (everyone denied) until it responds.");
                }
                authManager = new AuthManager(this, authDatabase, authConfig);
                getServer().getPluginManager().registerEvents(
                        new AuthGateListener(this, authDatabase, authManager, authConfig), this);
                PluginCommand loginCmd = getCommand("login");
                if (loginCmd != null) {
                    LoginCommand loginHandler = new LoginCommand(authManager);
                    loginCmd.setExecutor(loginHandler);
                    loginCmd.setTabCompleter(loginHandler);   // no player-name suggestions on a password field
                } else {
                    getLogger().severe("Command 'login' not found in plugin.yml — login gate cannot accept logins.");
                }
                getLogger().info("Auth login gate enabled (limbo " + authConfig.limboSeconds + "s, "
                        + authConfig.maxAttempts + " attempts / " + authConfig.windowMinutes + "m → "
                        + authConfig.lockoutMinutes + "m lockout).");

                // Bedrock cross-play: register the Floodgate handshake bridge so Bedrock clients adopt
                // their canonical RID (Java identity) at the netty layer. Only when enabled + Floodgate
                // is present; otherwise Bedrock stays denied by the gate's fail-closed check.
                if (authConfig.crossplayEnabled && getServer().getPluginManager().getPlugin("floodgate") != null) {
                    com.raeyd.raidattack.auth.BedrockLinkHandshake.registerIfPossible(this, authDatabase);
                    // A linked Bedrock player is seen as Java, so re-apply their real Bedrock skin via
                    // SkinsRestorer (else they'd be skinless — the offline RID has no Mojang skin).
                    if (getServer().getPluginManager().getPlugin("SkinsRestorer") != null) {
                        com.raeyd.raidattack.auth.BedrockSkinSync skinSync = new com.raeyd.raidattack.auth.BedrockSkinSync(this);
                        getServer().getPluginManager().registerEvents(skinSync, this);
                        skinSync.registerSkinRefreshHook();
                        getLogger().info("Auth: Bedrock skin sync enabled (GeyserMC → SkinsRestorer).");
                    } else {
                        getLogger().warning("Auth: SkinsRestorer not installed — linked Bedrock players will appear skinless.");
                    }
                } else if (authConfig.crossplayEnabled) {
                    getLogger().warning("Auth: crossplay_enabled is true but Floodgate isn't installed — Bedrock cross-play is OFF.");
                }
            } catch (Throwable t) {
                // Never let an auth-init failure disable the whole plugin OR silently open the
                // server: log it and deny every login (fail-closed) while raids/turrets load.
                getLogger().severe("Auth gate FAILED to initialize — denying all logins (fail-closed): " + t);
                authDatabase = null;   // shared pool is closed by onDisable, not here
                authManager = null;
                getServer().getPluginManager().registerEvents(
                        new com.raeyd.raidattack.auth.FailClosedLoginListener(), this);
            }
        } else {
            getLogger().info("Auth login gate DISABLED (auth.enabled: false) — server is open.");
        }

        zoneListener.refreshAll();
        getLogger().info("RaidAttack enabled.");
    }

    @Override
    public void onDisable() {
        // Restore any frozen player's inventory FIRST so a server stop never eats their items,
        // then close the DB pool.
        if (authManager != null) authManager.shutdownRestoreAll();
        if (pvpMode != null) pvpMode.stop();
        if (computePool != null) computePool.shutdown();
        if (eventManager != null) eventManager.stop();
        if (endPhantoms != null) endPhantoms.stop();
        if (homeTeleport != null) homeTeleport.stop();
        if (hoverWatchdog != null) hoverWatchdog.stop();
        if (citizensAuditor != null) citizensAuditor.stop();
        if (raidLoot != null) raidLoot.stop();
        if (raidSpawnEngine != null) raidSpawnEngine.stop();
        if (raidBossBars != null) raidBossBars.stop();
        if (raidEntities != null) raidEntities.stop();
        if (witherCombat != null) witherCombat.stop();
        if (turretBuffs != null) turretBuffs.stop();
        if (turretCombat != null) turretCombat.stop();
        if (turretEnemies != null) turretEnemies.stop();
        if (turretQueue != null) turretQueue.stop();
        if (turretEntities != null) turretEntities.stop();
        if (happyGhasts != null) happyGhasts.stop();
        if (quests != null) quests.stop();
        if (lagSimulator != null) lagSimulator.clearAll();
        // armorDurability now persists per-change to the DB (no onDisable save needed).
        if (claimManager != null) claimManager.stop();   // cancels flush loop + final synchronous sync
        // allianceManager persists per-change to the DB (no onDisable save needed).
        if (raidManager != null) raidManager.flush();   // cooldowns (active raids are ephemeral)
        // Close the shared DB pool last, after auth limbo restore + any final manager saves.
        if (sharedDbPool != null) sharedDbPool.close();
        getLogger().info("RaidAttack disabled.");
    }

    /**
     * Move every file from {@code plugins/HomeSystem/} into the current data folder once,
     * to preserve existing claims / names / configs when the plugin was renamed. No-op if the
     * destination already has files or the legacy folder is absent.
     */
    private void migrateLegacyDataFolder() {
        java.io.File dest = getDataFolder();
        java.io.File legacy = new java.io.File(dest.getParentFile(), "HomeSystem");
        if (!legacy.exists() || !legacy.isDirectory()) return;
        if (dest.exists() && dest.listFiles() != null && dest.listFiles().length > 0) return;
        if (!dest.exists()) dest.mkdirs();
        java.io.File[] kids = legacy.listFiles();
        if (kids == null) return;
        for (java.io.File f : kids) {
            java.io.File out = new java.io.File(dest, f.getName());
            if (!f.renameTo(out)) {
                getLogger().warning("Could not migrate " + f.getName() + " from legacy HomeSystem folder.");
            }
        }
        // Leave the (now-empty) legacy folder alone — it's harmless and a marker we ran.
        getLogger().info("Migrated legacy plugins/HomeSystem/ contents into plugins/" + dest.getName() + "/.");
    }

    public ClaimManager getClaimManager() { return claimManager; }
    public ZoneListener getZoneListener() { return zoneListener; }
    public LagSimulator getLagSimulator() { return lagSimulator; }
    public HoverWatchdog getHoverWatchdog() { return hoverWatchdog; }
    public TurretPlacementQueue getTurretQueue() { return turretQueue; }
    public TurretEntityManager getTurretEntities() { return turretEntities; }
    public TurretCombatManager getTurretCombat() { return turretCombat; }
    public TurretEnemyTracker getEnemyTracker() { return turretEnemies; }
    public WitherCombatManager getWitherCombatManager() { return witherCombat; }
    public AllianceManager getAllianceManager() { return allianceManager; }
    public RaidEntityManager getRaidEntities() { return raidEntities; }
    public RaidManager getRaidManager() { return raidManager; }
    public RaidBossBars getRaidBossBars() { return raidBossBars; }
    public RaidSpawnEngine getRaidSpawnEngine() { return raidSpawnEngine; }
    public RaidLootManager getRaidLoot() { return raidLoot; }
    public CitizensAuditor getCitizensAuditor() { return citizensAuditor; }
    public RightsManager getRightsManager() { return rightsManager; }
    public ModerationService getModerationService() { return moderationService; }
    public ArmorDurabilityManager getArmorDurability() { return armorDurability; }
    public QuestManager getQuests() { return quests; }
    public HomeTeleportManager getHomeTeleport() { return homeTeleport; }
    public EventManager getEventManager() { return eventManager; }
    public SpawnAreaManager getSpawnArea() { return spawnArea; }
    public ComputePool getComputePool() { return computePool; }
    public WorldDatabase getWorldDatabase() { return worldDatabase; }

    public TacticalLeaveManager getTacticalLeaves() { return tacticalLeaves; }
    public PvpModeManager getPvpMode() { return pvpMode; }

    public AuthManager getAuthManager() { return authManager; }

    /** Master switch for all {@code dev} commands/subcommands. Default OFF. When off, dev commands
     *  are fully disabled — functionally and hidden from tab-completion. */
    public boolean isDevMode() { return getConfig().getBoolean("dev_mode", false); }

    /** The x2 dev shield used for tab-completion + a quick gate: dev mode on AND the caller is an
     *  owner per our own rights system (we deliberately don't check op — an owner is always op). */
    public boolean devCommandsAllowed(CommandSender sender) {
        return isDevMode() && rightsManager != null && rightsManager.isOwner(sender);
    }

    /** Whether raider path REPLANS run on the worker pool (off the main thread). Config-toggleable;
     *  defaults on. When off, all pathfinding is synchronous (the pre-multicore behaviour). */
    public boolean isAsyncPathfinding() { return getConfig().getBoolean("async_pathfinding", true); }


    /**
     * Wipe every turret in this claim before removing it: raze structures, despawn NPCs,
     * cancel pending placements. Required because plain {@code claimManager.removeClaim()}
     * just deletes the claim entry — leaving turret structures + Citizens NPCs orphaned in
     * the world forever (the "outdated turret tower" bug). Used by {@code /HomeSystem unclaim}
     * and {@code /HomeSystem dev clear}.
     */
    public boolean removeClaimWithCleanup(UUID owner) {
        Claim claim = claimManager.getClaimOf(owner);
        if (claim == null) return false;
        org.bukkit.World world = getServer().getWorld(claim.getWorldId());
        java.util.List<Turret> snapshot = new java.util.ArrayList<>(claim.getTurrets());
        getLogger().info("unclaim(" + claimManager.resolveName(owner) + "): cleaning "
                + snapshot.size() + " turret slot(s) before removing claim.");
        int cleared = 0, cancelled = 0;
        for (Turret t : snapshot) {
            boolean wasPending = turretQueue != null && turretQueue.cancel(t);
            if (wasPending) { cancelled++; continue; }
            if (world != null) TurretStructure.clear(world, t);
            if (turretEntities != null) turretEntities.despawn(t);
            cleared++;
        }
        // Defensive: null out the slots themselves on the in-memory Claim. removeClaim() drops
        // the claim from the map next, but if any background task is mid-iteration holding a
        // stale reference to this Claim, save() called on it later wouldn't write turret coords.
        // The slot LEVELS array is untouched — removeClaim() snapshots it into savedSlotLevels
        // so the owner's next /HomeSystem claim restores their upgrades.
        claim.clearTurretSlots();
        boolean removed = claimManager.removeClaim(owner);
        // Moving the base away forfeits any active re-raid protection. The cooldown is keyed by
        // owner, so leaving it in place would let a raided player unclaim + re-claim elsewhere
        // and carry the protection along — chaining that would keep them unraidable forever.
        if (removed && raidManager != null) raidManager.clearZoneCooldown(owner);
        // Second safety net: kill any Citizens NPC named "Turret #..." that's now unreferenced.
        // Catches the rare case where despawn() above silently failed (Citizens reload mid-tick,
        // chunk unloaded at the wrong moment, etc.) and would otherwise leave a free-floating
        // shulker bossing around outside any claim.
        int orphans = turretEntities != null ? turretEntities.wipeOrphanNPCs() : 0;
        getLogger().info("unclaim(" + claimManager.resolveName(owner) + "): cleared="
                + cleared + " pending-cancelled=" + cancelled + " orphan-NPCs-wiped="
                + orphans + " claim-removed=" + removed);
        return removed;
    }

    /**
     * True iff {@code loc} falls inside any deployed turret's protected zone — i.e. the 5×5 XZ
     * column from the turret's anchor up to {@link TurretStructure#ZONE_MAX_DY} blocks above.
     * Single source of truth for the protection listener (block-edit denials) and the combat
     * manager (projectile pass-through). Linear scan over all claims × turrets, fine at this scale.
     */
    public boolean isInTurretZone(Location loc) {
        if (loc.getWorld() == null) return false;
        UUID worldId = loc.getWorld().getUID();
        int x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();
        int half = TurretStructure.FOOTPRINT_RADIUS;
        int maxDy = TurretStructure.ZONE_MAX_DY;
        for (Claim claim : claimManager.all().values()) {
            if (!claim.getWorldId().equals(worldId)) continue;
            for (Turret t : claim.getTurrets()) {
                int dx = x - t.getX();
                int dz = z - t.getZ();
                int dy = y - t.getY();
                if (dx >= -half && dx <= half
                        && dz >= -half && dz <= half
                        && dy >= 0 && dy <= maxDy) return true;
            }
        }
        return false;
    }
}
