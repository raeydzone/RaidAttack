# Plugin Set

Third-party plugins this server runs (on **Paper**). Exact versions are intentionally left out —
they change with every Minecraft/Paper bump, so always install the build that matches the current
server version.

- **Citizens** — NPC framework. RaidAttack uses it for turret and raid NPCs.
- **SkinsRestorer** — restores player skins on this offline-mode (`online-mode=false`) server,
  which would otherwise show default skins.
- **Geyser** (Geyser-Spigot) — lets Bedrock Edition clients connect to this Java server
  (cross-play). Listens on UDP **19132**; downloads the matching Java client assets on first
  start (needs outbound internet). Configured with `java.auth-type: floodgate`.
- **Floodgate** — gives Bedrock players a stable XUID-based UUID + the Floodgate API the
  RaidAttack plugin uses to bind a Bedrock connection to its dual-username account. Generates a
  keypair on first boot (shared with Geyser on the same server automatically).

The `RaidAttack` plugin itself is first-party (built from this repo) and is not listed here.

## Bedrock + the login gate (dual-username accounts — in progress)

A RaidAttack account carries **two identities**: a Java username (→ offline UUID) and a Bedrock
gamertag (→ Floodgate XUID UUID). Registration in Discord asks for both; the plugin resolves a
connecting player (Java or Bedrock, the latter detected via the Floodgate API) to the same
account, enforces **one concurrent session per account** (so the two editions can't both be on —
the anti-dupe lynchpin), and syncs the full profile (inventory, ender chest, XP, health/hunger,
effects, position) across editions so a player can swap Java ↔ Bedrock freely.

Status: Floodgate + Geyser (floodgate auth-type) are installed (Phase 1). The bot dual-username
registration, the plugin's identity/session resolution, and the cross-edition profile sync are
the remaining phases.
