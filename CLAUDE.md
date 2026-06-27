# RaidAttack

Codebase for the **RaidAttack** Paper plugin plus the local Minecraft micro-server used to
develop and test it. The runnable server lives at `/home/raidattack` and is reachable from
here as the `server/` symlink.

## Scope (read first)

This assistant is the **codebase / plugin agent** for RaidAttack. In-scope: the `RaidAttack`
Paper plugin and any other plugins, the server config/scaffold, build & deploy, and
running/restarting the dev server.

**Out of scope — decline these.** Video editing/production, motion graphics, title
cards/overlays, thumbnails, logos or other art assets, marketing copy, and anything else not
about the server or its plugin code. If such a request comes in, briefly say it's outside this
agent's scope (codebase/plugin only) and likely meant for a different agent, and stop. Don't
create video/art/asset files in this repo.

**Exception — Bedrock resource pack assets ARE in scope.** Creating art/texture assets (PNGs:
panels, buttons, backgrounds, icons, `pack_icon`, etc.) is allowed *specifically* for the
server's Bedrock resource pack (the custom JSON UI in `bedrock-pack/`, served to Bedrock players
via Geyser). This is the one carve-out from the no-art-assets rule — it's server UX for the
plugin's Bedrock menu. The general art-asset prohibition still applies everywhere else.

## Stack

- **Server software:** Paper `26.1.2` build #64 (`server/paper-26.1.2-64.jar`).
- **Minecraft version:** 26.1.2 (pinned — Citizens does not yet stably support 26.2; Citizens
  `2.0.42` / b4180 supports up to 26.1.x / 1.21.10).
- **Plugins:** third-party Citizens + SkinsRestorer, plus our first-party `RaidAttack` plugin.
  The third-party set (no pinned versions — they track the MC/Paper version) is listed in
  [`docs/plugin-set.md`](docs/plugin-set.md). Citizens is the version-sensitive one: `2.0.42`/
  b4180 supports up to 26.1.x / 1.21.10, which is why the server is pinned to 26.1.2.
- **Java (Linux / this box):**
  - **JDK 25** (`/usr/lib/jvm/java-25-openjdk-amd64`) — required to **run** Paper 26.1+
    ("Minecraft 26.1 and newer requires running the server with Java 25 or above") and is the
    compile **toolchain** (`build.gradle.kts` targets Java 25 / class-file 69).
  - **JDK 21** (`/usr/lib/jvm/java-21-openjdk-amd64`) — used only to run the **Gradle 9.0.0
    daemon**, which doesn't support running on JDK 25. Gradle auto-detects JDK 25 for the
    toolchain; run the wrapper with `JAVA_HOME` pointed at 21.

## The plugin

- **Name:** `RaidAttack` (was `RaidAttackS2`; was `HomeSystem` before that — `onEnable` still
  migrates a legacy `plugins/HomeSystem/` data folder forward).
- **Version convention:** the plugin version **mirrors the Minecraft version it targets** —
  currently `26.1.2`. Bump it in lockstep with the server's MC/Paper version.
- **Main class:** `com.raeyd.raidattack.HomeSystemPlugin` (kept its legacy class name; only
  the package was renamed during the cleanup).
- **Source layout** (`plugins-src/RaidAttack/src/main/java/com/raeyd/raidattack/`):
  ```
  HomeSystemPlugin.java   # main / JavaPlugin entry point (root package)
  turret/        # turrets: combat, projectiles, upgrades, placement, listeners (17)
  raid/          # raids: spawn engine, raiders, loot, GUIs, commands (15)
  combat/        # wither / phantom / ghast / damage / armor balance (6)
  claim/         # claims, zones, rights, home teleport, spawn area (8)
  alliance/      # alliances + pvp listener (4)
  moderation/    # moderation service / command / listener (3)
  season/        # season events + gating (4)
  quest/         # quests (2)
  pathfinding/   # nav grid + path planners (3)
  core/          # plugin-wide infra: compute pool, chat, NMS reflection, etc. (6)
  ```
  Permission nodes are still `homesystem.*` (player-facing identifiers — do **not** rename).

## Server (`/home/raidattack`, symlinked as `server/`)

The runnable Paper instance. Key `server.properties`: **offline mode** (`online-mode=false`),
`level-seed=-8113461364597736953`, `motd=Raid Attack 2`, `max-players=50`, `difficulty=hard`,
`view-distance=16`. World folders, logs, caches and identity JSON are not source. There is no
`CLAUDE.md` inside the server folder — it is runtime only.

`server/start.sh` launches Paper pinned to **4 CPU cores** (`taskset -c 0-3` +
`-XX:ActiveProcessorCount=4`) with a **12 GB** heap on JDK 25. `AlwaysPreTouch` is deliberately
omitted (the host has ~15 GiB RAM, so committing the full heap up front risks the OOM killer).

## Build / deploy / run

```bash
cd plugins-src/RaidAttack
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew deploy
```
`deploy` compiles (toolchain JDK 25) and copies `RaidAttack.jar` into `server/plugins/`. The
build compiles against `server/libraries/*.jar` (Paper's extracted libraries) and the Citizens
jar — both `compileOnly`. If `server/libraries/` is missing, regenerate it without a full boot:
`java -Dpaperclip.patchonly=true -jar server/paper-26.1.2-64.jar` (JDK 25).

**Every plugin compile is followed by a redeploy AND a server restart** — Bukkit only loads
jars at startup, so the running server keeps executing old code until restarted. This full
compile → deploy → restart cycle is the assistant's job by default; don't stop at "build
successful". To restart: `kill -TERM <paper pid>` (Paper shuts down cleanly; the dev world is
disposable), wait for exit, then `server/start.sh`. Confirm the boot with the
`[RaidAttack] Enabling RaidAttack v26.1.2` and `Done (...)` log lines.

## Gotchas

- Don't upgrade Paper past 26.1.x while Citizens hasn't shipped 26.2 support. Keep MC version,
  Paper build, Citizens build, and the plugin version aligned; Citizens has historically been
  the blocker, so verify it first when bumping MC.
- Don't run Gradle on JDK 25 (the 9.0.0 daemon won't start) and don't run Paper on JDK 21
  (Paper 26.1+ refuses anything below 25).
- First boot generates `server.properties`, `bukkit.yml`, `spigot.yml`, `paper-global.yml`,
  `paper-world-defaults.yml` and the `world*/` folders. Worlds are runtime; the tuned yml
  configs are worth keeping.

## Docs

- [`docs/plugin-set.md`](docs/plugin-set.md) — the third-party plugins this server runs
  (Citizens, SkinsRestorer), without pinned versions.
