# Dependency Inventory

So the RaidAttack server + plugin can be rebuilt / moved to another Linux box without
rediscovering requirements. Update whenever a dependency is added, removed, or materially changed.
(Not yet under source control — this file is here for when the repo is created.)

## Platform / toolchain
- **Paper** `26.1.x` (the server jar, `server/paper-*.jar`). Pinned to 26.1.x because Citizens
  doesn't yet support 26.2. See `CLAUDE.md` for the exact build.
- **Java 25** (`/usr/lib/jvm/java-25-openjdk-amd64`) — runs Paper (26.1+ requires Java 25+) and is
  the compile toolchain (class-file 69).
- **Java 21** (`/usr/lib/jvm/java-21-openjdk-amd64`) — only to run the Gradle 9.0.0 daemon (it
  won't run on JDK 25). Build with `JAVA_HOME=…java-21… ./gradlew deploy`.
- **Gradle 9.0.0** via the wrapper.

## Third-party plugins
Listed (without pinned versions) in [`docs/plugin-set.md`](docs/plugin-set.md): **Citizens**,
**SkinsRestorer**, **Geyser** (Geyser-Spigot), and **Floodgate**, on Paper. Versions track the
Minecraft/Paper version. Operational notes:
- **Geyser** needs an open **UDP port 19132** (the Bedrock listener) and **outbound internet** on
  first start (downloads the matching Java client JAR to extract assets). Configured with
  `java.auth-type: floodgate` in `plugins/Geyser-Spigot/config.yml`.
- **Floodgate** provides Bedrock players a stable XUID-based UUID + the API the RaidAttack plugin
  uses for dual-username accounts. Generates a keypair on first boot (auto-shared with Geyser on
  the same server). Bedrock play is gated by the same Discord login as Java, via the player's
  Bedrock identity bound to their account (see `docs/plugin-set.md`).

## Plugin runtime libraries (downloaded by Paper)
Declared in `src/main/resources/plugin.yml` under `libraries:` and pulled from Maven Central at
load time (no shadow jar). Mirrored as `compileOnly` in `build.gradle.kts`:
- `org.postgresql:postgresql` — JDBC driver for the auth + world databases.
- `com.zaxxer:HikariCP` — connection pool.
- `org.mindrot:jbcrypt` — verifies the `$2a$` BCrypt password hashes the Discord bot writes.

## PostgreSQL — now REQUIRED (auth + gameplay state)
Single database **`raidattack`** on `127.0.0.1:5432`, two schemas:
- **`public`** — the Discord-gated auth core (`accounts`, `account_flags`, `login_attempts`).
  Owned by role **`raidattack`** (the Discord bot, `raeydbot`, owns/creates these).
- **`world`** — gameplay state that replaced the legacy `.yml` files (claims, turrets, alliances,
  raids, quests, rights, names, armour indicator, …). Owned by role **`ra_plugin`**.

The **plugin** connects as **`ra_plugin`** with ONE pool (`data/RaidAttackPool`): narrow on
`public.accounts` (SELECT + UPDATE of only the 4 auth columns, INSERT on `account_flags` /
`login_attempts`) and full owner of schema `world`. It cannot read/modify password hashes.

Provisioning a fresh DB (the bot creates the `public`/auth tables + grants on its first run; the
plugin creates its own `world` tables on enable):
```sql
CREATE ROLE raidattack LOGIN PASSWORD '...';      -- bot / auth owner
CREATE DATABASE raidattack OWNER raidattack;
CREATE ROLE ra_plugin LOGIN PASSWORD '...';        -- plugin (gameplay + narrow auth reads)
\c raidattack
CREATE SCHEMA world AUTHORIZATION ra_plugin;       -- plugin owns gameplay schema
-- ra_plugin's grants on public.* are (re)applied by the bot on startup.
```

## Secrets / local config (gitignored)
- DB credentials for the plugin live ONLY in the runtime `server/plugins/RaidAttack/config.yml`
  under `auth.database` (host/port/database/user/password). The bundled
  `src/main/resources/config.yml` ships a placeholder password — never commit a real one.
- `config.yml` is the ONLY persistent file the plugin keeps; it holds server-off-editable settings
  (`events:`, `auth:`, etc.). Everything else now lives in the `world` schema.

## Build / deploy / run
See `CLAUDE.md`. In short: `cd plugins-src/RaidAttack && JAVA_HOME=…java-21… ./gradlew deploy`,
then restart the server (`server/start.sh`, pinned to 4 cores / 12 GB heap on JDK 25).
