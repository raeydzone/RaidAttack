# RaidAttack

**RaidAttack** — a Paper server plugin (Java + Bedrock).

This repo is the plugin codebase plus the local Minecraft micro-server used to develop and test
it; the runnable server lives at `/home/raidattack`, reachable here as the `server/` symlink.

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

- **Server software:** Paper — the build jar in `server/` (`server/paper-*.jar`). The exact
  MC/Paper version it targets is whatever `build.gradle.kts` (`version`) says; don't re-pin the
  number here (see [Code quality](#code-quality)).
- **Plugins:** third-party Citizens + SkinsRestorer, plus our first-party `RaidAttack` plugin
  (third-party set in [`docs/plugin-set.md`](docs/plugin-set.md), no pinned versions — they
  track the MC/Paper version). **Citizens is the version-sensitive one** and lags the latest
  Minecraft major, which is why MC/Paper stays on the line Citizens currently supports — so when
  bumping the version, verify Citizens first.
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
- **Version convention:** the plugin version **mirrors the Minecraft version it targets**, set in
  one place (`build.gradle.kts` `version`, injected into `plugin.yml` at build) — bump it there in
  lockstep with the server's MC/Paper version; don't hard-code the number elsewhere.
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

## Code quality

Write **clean, readable code** — that is the bar; favour clarity over cleverness.

- **Names — long and clear over short and cryptic, prefixed by subsystem.** A name should say
  where it belongs: turret-system code starts with `turret…`, raid-system with `raid…`, claims
  with `claim…`, alliances with `alliance…`, quests with `quest…`, and so on. Prefer a slightly
  longer name that reads plainly over a short abbreviation whose meaning isn't obvious.
- **Comments — short, and only where they earn their place.** A one-liner on what a function does,
  or a note on code written a **specific, non-obvious way because it breaks otherwise**. Some code
  looks illogical at first glance but is deliberately exact — flag those so nobody "simplifies"
  them into a bug. Don't narrate the obvious.
- **No volatile values in docs.** Never hard-code things that change — the current MC / plugin /
  Paper version above all — in this file or other docs. The single source of truth is
  `build.gradle.kts` (`version`). Refer to "the current version" generally; repeating a number in
  several places just goes stale and invites a needless "fix" + push.

## Working style

- **Parallelize with sub-agents when a task splits cleanly.** If work separates into independent
  files/areas (e.g. the Discord bot vs. the plugin, DB schema vs. Java code, or Bedrock-pack asset
  generation vs. code), launch sub-agents for the separable parts and work the rest yourself — it
  speeds the whole task up. Give each agent an exact file scope + contract so agents never edit
  the same file concurrently. Same for research: open questions (Paper/Geyser APIs, external docs)
  go to research agents in the background while implementation continues.

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
`java -Dpaperclip.patchonly=true -jar server/paper-*.jar` (JDK 25).

**Every plugin compile is followed by a redeploy AND a server restart** — Bukkit only loads
jars at startup, so the running server keeps executing old code until restarted. This full
compile → deploy → restart cycle is the assistant's job by default; don't stop at "build
successful". To restart: `kill -TERM <paper pid>` (Paper shuts down cleanly; the dev world is
disposable), wait for exit, then `server/start.sh`. Confirm the boot with the
`[RaidAttack] Enabling RaidAttack v<version>` and `Done (...)` log lines.

## Version control (commit policy)

**Every change to this repo is committed AND pushed to GitHub** — treat it as part of finishing
the work, not an optional extra. After the compile → deploy → restart cycle for a change, commit
the source and push it to the private repo (`github.com/raeydzone/RaidAttack`) so the repo always
matches what's deployed. Don't batch unrelated changes into one commit; keep each commit scoped to
one logical change with a clear message.

The push mechanics (commit identity **`RaeydZone <raeydzone@gmail.com>`**, `gh` logged in as
`raeydzone`, push only from **inside** `CODE_PROJECTS/RaidAttack` — never from `/home`) live in the
mother-folder [`/home/CLAUDE.md`](../../CLAUDE.md) → "GitHub push". Only source is tracked; the
runnable server under `server/` (worlds, logs, third-party plugin configs like SkinsRestorer's
`config.yml`, `server.properties`) is runtime, not committed here.

## Gotchas

- Don't upgrade Paper to a Minecraft major Citizens doesn't yet support. Keep MC version, Paper
  build, Citizens build, and the plugin version aligned; Citizens has historically been the
  blocker, so verify it first when bumping MC.
- Don't run Gradle on JDK 25 (the 9.0.0 daemon won't start) and don't run Paper on JDK 21
  (Paper 26.1+ refuses anything below 25).
- First boot generates `server.properties`, `bukkit.yml`, `spigot.yml`, `paper-global.yml`,
  `paper-world-defaults.yml` and the `world*/` folders. Worlds are runtime; the tuned yml
  configs are worth keeping.

## Docs

- [`docs/plugin-set.md`](docs/plugin-set.md) — the third-party plugins this server runs
  (Citizens, SkinsRestorer), without pinned versions.
- Player guides (human-readable, numbers verified against code — update them when the
  mechanics they describe change):
  [`docs/guide-home-protection.md`](docs/guide-home-protection.md),
  [`docs/guide-alliances.md`](docs/guide-alliances.md),
  [`docs/guide-raids-and-turrets.md`](docs/guide-raids-and-turrets.md),
  [`docs/guide-pvp-mode.md`](docs/guide-pvp-mode.md).
