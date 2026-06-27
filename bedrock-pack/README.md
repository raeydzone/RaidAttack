# RaidAttack Bedrock UI pack

Custom JSON UI resource pack that re-skins the native `/ra` menu for Bedrock players
(served through Geyser). Java players never receive a form, so this pack only affects
the Bedrock experience.

## What it does

- **Pack icon** (`pack_icon.png`) — the square RAID ATTACK badge.
- **Menu banner** — `ui/server_form.json` redefines `long_form_scrolling_content` (copied
  from Mojang's vanilla `server_form.json`) to prepend the wide RAID ATTACK wordmark logo
  (`textures/ui/raidattack_logo.png`) above every SimpleForm. The rest of the form renders
  vanilla, so this is the safe, proven layout.

## Files

```
manifest.json                 header + module version (mirrors the MC version)
pack_icon.png                 pack thumbnail
ui/_global_variables.json     theme flags
ui/server_form.json           ACTIVE menu skin (wider dialog + logo banner + vanilla list) ← loaded
ui/server_form.list.json      pristine vanilla banner fallback (no width override)        ← NOT loaded
font/                         hook for a custom Bedrock font (no glyphs yet)
textures/ui/raidattack_logo.png   wide wordmark logo (menu banner)
```

Bedrock only auto-loads `ui/server_form.json` (it overrides the vanilla `server_form`
namespace). Any other `ui/*.json` file (like `server_form.list.json`) is inert until you
make it the active file.

## On the 2-column grid (reverted)

A 2-column grid was tried and **reverted**: a collection-backed `grid` with
`grid_rescaling_type: "horizontal"` renders **empty** without fixed `grid_dimensions`, and a
fixed `[2, N]` grid leaves empty trailing cells on every menu that isn't an exact multiple of
the column count. So the active layout is the proven vanilla vertical list (buttons always
render), just wider + with the banner. A real 2×2 needs a **title-scoped custom layout for the
main menu only** (detect the menu by a marker in its title, hand-place 4 boxes) — tracked as
future work, and it also satisfies the "only affect the /ra menu, no global changes" goal.

To revert to the pristine banner-only fallback: `cp ui/server_form.list.json ui/server_form.json`, bump
the version, repackage.

## Packaging & deploy

No `zip` binary on the box — use Python's `zipfile` (manifest must sit at the zip root):

```bash
python3 - <<'PY'
import zipfile, os
src = "bedrock-pack"
out = "/home/raidattack/plugins/Geyser-Spigot/packs/RaidAttackUI.mcpack"
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for root, _, files in os.walk(src):
        for f in files:
            full = os.path.join(root, f)
            z.write(full, os.path.relpath(full, src))
print("wrote", out)
PY
```

Geyser serves the `.mcpack` to Bedrock players (`force-resource-packs: true`).

## Versioning (important)

The manifest `version` (header **and** module) mirrors the Minecraft version — currently
`[26, 1, 2]` — NOT the Bedrock engine version (`min_engine_version`). **Bedrock caches packs
by UUID + version, so you MUST bump the version whenever you change a loaded file**, or
clients keep the old cached pack. Bump it in lockstep with the MC/plugin version.

## Caveats

- JSON UI is brittle and version-sensitive. A bad `ui/server_form.json` can blank **all**
  Bedrock forms (only the `/ra` menu here — login uses chat, not forms). Keep the proven
  banner version as the fallback.
- Geyser doesn't log pack loading (it's lazy on Bedrock connect); verify with a real Bedrock
  join, or set Geyser `debug-mode: true` temporarily.
