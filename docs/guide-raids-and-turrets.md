# Player Guide — Raids & Turrets

Everything you need to know about attacking another player's base and defending your own.
Numbers in this guide come straight from the plugin — they are the real values used in game.

---

## Part 1 — Attacking: how raids work

### What a raid is

You don't attack a base alone. When you start a raid, the server spawns an army of **Raiders**
(custom NPC soldiers carrying your name — "*YourName*'s Raider") inside the target's zone. They
fight **for you**: they attack the defenders and smash the base's turrets while you (and any
friends you bring) can join the fight in person. Your goal is to knock out **all** of the
base's turrets at the same time — that's when the base is *breached* and its chest loot starts
flowing to you automatically.

### Starting a raid

1. Type `/raid`. A chest menu opens listing every claimed base on the server:
   - **Dirt block** — you can raid this base. Click it to start.
   - **Allium (flower)** — friendly to you (your own base, a friend's, or an alliance
     member's). You can't raid it.
   - **Magma block** — this base is already being raided by someone. One raid per base at a
     time.
2. Click a dirt tile. If you pass all the checks below, the raid begins immediately.

Bedrock players: the `/ra` menu's Raids screen opens the same list, plus a **Raid Selector**
slider that lets you choose exactly how much to spend (see cost below). Typing `/raid`
directly works the same on both editions.

### What it costs

A raid is paid in **diamonds + emeralds, 1:1**, taken from your inventory the moment you click:

- **Minimum:** 32 diamonds + 32 emeralds.
- **Maximum:** 64 diamonds + 64 emeralds.
- On Java, clicking a target automatically spends the most you can afford (capped at 64).
  On Bedrock (via the `/ra` menu) you pick the spend on a slider first.
- Only your main inventory counts — items in your offhand or worn armor are not spent.

### What your units buy

Each diamond+emerald pair is one **unit**. Units scale the size of your raider army linearly:

| Units spent | Raiders active at once | Total raiders spawned |
|---|---|---|
| 32 | 9 | 128 |
| 64 | 12 | 256 |

(Values in between scale smoothly — e.g. 48 units gives about 11 active / 192 total.)

The army spawns in waves: whenever the number of live raiders drops below the "active" goal, a
replacement spawns within 1–3 seconds at the edge of the target's zone, until the total budget
is used up. Each spawn is 95% a normal Raider (25 HP, 6 damage per hit) and 5% a **Ravager**
(100 HP, 18 damage) — a much heavier hitter.

### Requirements before you can raid someone

- **Raiding must be unlocked for the season.** Early in a season `/raid` is locked; using it
  tells you the exact unlock date and a countdown.
- **The defender must be online.** You can't raid an empty base.
- **36-hour protection.** A base that was raided can't be raided again for 36 hours (counted
  from raid start). You'll be told how long is left if you try. If the owner unclaims and
  moves their base, that protection is forfeited.
- **Not friendly.** You can't raid your own base, anyone who has you on their friend list,
  anyone on *your* friend list, or anyone in your alliance.
- **One raid at a time.** You can't start a second raid while yours is running, and you can't
  start any raid while your own base is under attack.
- **Not already raided.** Only one raid can target a base at once.

Also: while a raid you're part of is running, **friend lists and alliance membership are
frozen** for both sides — no adding or removing base members mid-fight.

### The flow of a raid

1. **Announcement.** The whole server sees "*Attacker* is about to raid *Owner*'s base!". The
   defending side (owner + friends + alliance) also hears loud raid horns.
2. **Incoming phase.** A random **90–180 second** delay before the first raider appears — a
   different random delay every raid. A red boss bar is already up so the defenders see it
   coming. Nothing can be stolen during this countdown.
3. **Active phase.** Raiders spawn at the zone perimeter and push in, attacking defenders and
   turrets. The boss bar tracks how much of the raider army is left.
4. **End.** The raid ends when **every raider is dead** — that's a successful defense. There
   is no timer working against the defender; they win by killing the whole army.

The boss bar is shown to the defender's side and to anyone standing inside the zone. As the
attacker you don't see it from outside — step into the zone and you will.

### Destroying turrets

Turrets are the base's defense (full details in Part 2). During your raid:

- **Only your raiders (and a Wither, if one is brought in) damage turret structures** — you
  can't punch a turret down yourself. Protect your raiders and thin out defenders so the
  raiders can do their work.
- Each turret has structure HP shown on a floating label. At 0 HP it is destroyed: it goes
  offline, fires a final panic volley of 4 bullets in all directions, and stays down for
  **5 minutes** before respawning at full HP.
- **Breach = every turret down at the same moment.** Because turrets respawn after 5 minutes,
  you must keep all of them offline simultaneously. Downing #1–#3 fast and reaching #4 six
  minutes later doesn't count — #1 is already back up.

### When the base is breached

The moment all of the target's turrets are down at once (a base with *no* turrets counts as
breached as soon as the raid goes active):

- **+500 XP "Successfully Raided" bonus** — paid once per raid to you, and to any of your
  friends/allies who are inside the zone and have dealt at least 25 damage to defenders.
- **The base's coordinates are broadcast to the entire server** — everyone learns where it is.
- **Loot stealing starts.** Every 10 seconds, one slice of loot is automatically pulled from
  the base's chests into your personal raid inventory: stackable items leave 4–8 at a time,
  unstackable items (tools, weapons, armor) leave whole. All non-block items plus valuable
  blocks (ores/mineral blocks, obsidian, ancient debris, …) are taken; junk building blocks
  (dirt, cobble, planks…) are ignored.
- **Chest lockout.** While breached, *nobody* — not even the owner — can open chests or other
  containers in the base by hand. Loot leaves only through the automatic steal ticker, so a
  defender can't race you to rescue a chest.

Stealing (and the lockout) stops the instant any turret respawns; knock them all down again
and a fresh stealing window opens on whatever is left.

### Your raid inventory — `/raid inv`

Stolen items land in your persistent 27-slot **raid inventory**. Open it any time with
`/raid inv` and take items out. It's withdraw-only — you can't stash your own items in it. If
it fills up mid-raid, you're warned and further steals stay in the defender's chests until you
make room.

### Attacker bonuses (summary)

| Bonus | XP | How |
|---|---|---|
| Successfully Raided | +500 | All the target's turrets destroyed at the same moment (once per raid) |
| Sustain 15 min | +500 | The raid's active phase lasts 15 minutes (once per raid) |

Both are paid to the attacker always, and to attacker-side friends/allies only if they're
inside the zone and have dealt ≥25 damage to defender players during the raid.

---

## Part 2 — Defending: turrets and holding your base

### Turret basics

- Your claim has **4 turret slots** (turrets #1–#4). Turrets are optional but they are your
  only wall against a breach: a base with none is breached the moment a raid goes active.
- A turret is a 7-block-tall blackstone tower on a 5×5 base, with a shulker gunner inside and
  a glowstone cap. It shoots homing bullets at enemies. A floating label above it shows its
  structure HP.

### Deploying, listing, removing

- `/HomeSystem turret deploy` — places a turret where you stand (you must be inside your own
  claim). Deploying costs nothing. The structure materialises once you step off its 5×5
  footprint.
- Turrets must be at least **22 blocks apart** from each other.
- `/HomeSystem turret list` — shows each slot's level, position, HP, and (if destroyed) the
  respawn countdown, with a visual highlight in the world.
- `/HomeSystem turret remove <#>` — removes a turret. The slot's **level is kept** for the
  next deploy. You can't dodge destruction downtime this way: redeploying into a slot that's
  still in downtime gives a turret that only materialises when the downtime ends.

### Levels and upgrades

`/HomeSystem turret upgrade` opens the upgrade menu. Levels belong to the **slot** and survive
remove/redeploy.

| Level | Upgrade cost | Turret HP | Bullet damage | Fire rate |
|---|---|---|---|---|
| 1 | — (starting level) | 200 | 8 | every 4 s |
| 2 | 64 Diamonds | 250 | 10 | every 3 s |
| 3 | 1 Nether Star | 300 | 12 | every 2 s |

All levels share: **35-block range**, homing bullets at 6 blocks/second with a 1.5-block
splash. Bullets can be shot down — they have 30 HP and die to sword/arrow damage.

### What turrets shoot at

- **Enemy players** who have been inside your claim within the last **60 seconds**. A
  passer-by who never entered your zone is ignored; someone who poked in and ran is chased
  for up to a minute.
- **Raiders** attacking your base during a raid.
- **Hostile mobs**, optionally: `/HomeSystem turret attackmobs <on|off>`. When on, turrets
  shoot the nearest hostile mob whenever no enemy player is in range.
- Never you, your base friends, or your alliance — bullets pass straight through members.

Bonus: standing within 10 blocks of one of your own (or a friendly claim's) turrets gives you
**Resistance I** while you stay in the aura.

### The protected turret zone

Every deployed turret projects a **7×7 protected column** (one block wider than the structure
on every side) that spans the **entire world height**. Inside it, for *everyone* — including
the owner:

- **Nothing can be broken.** No mining, explosions, fire, or pistons touch it.
- **Nothing can be built.** No block placing, no buckets, no fluid flow, no pistons pushing in.

This means you can't box your turret in for safety — and attackers can't bury it, wall it off,
or flood it either. On deploy the column above the turret is cleared to air, so it always has
a free line of fire.

### When a turret is destroyed

Turret structure HP is damaged by **raiders during a raid** (and by a Wither's siege) —
players can't damage the structure directly. At 0 HP:

- A loud anvil-break sound plays and nearby players see
  "*Turret #N (Owner) got temporary destroyed by …*".
- The turret fires a last **panic volley** — 4 bullets fanning out in all directions.
- It goes offline for **5 minutes**, then respawns automatically at full HP.

### What "breached" means for you

If **all** your deployed turrets are down at the same time while a raid is active, your base
is breached:

- Your base's **coordinates are broadcast to the whole server**.
- The attacker earns the +500 XP "Successfully Raided" bonus.
- Loot starts draining from your chests to the attacker every 10 seconds — and while
  breached, **you can't open your own chests** to rescue anything.

The breach ends the moment one turret respawns. So the defense math is simple: every turret
you keep alive — or get back up — stops the bleeding. Spreading turrets out (they must be 22+
blocks apart anyway) makes it harder to keep them all down at once.

### Defender rewards

Kill the entire raider army and the raid is defeated. Every defender gets a "successfully
defended" message, and these bonuses are paid out:

| Bonus | XP | Condition |
|---|---|---|
| Made it to the end | +500 | You never died during the raid |
| Kept a turret standing | +500 | Your turrets were never all down at the same moment |

The base **owner** always qualifies for these checks. **Friends and alliance members** helping
defend must have personally killed **at least 10 raiders** and spent **at least half the raid
inside the zone** to be eligible. Raiders also drop XP orbs when killed (1–2 XP each, 4–8 for
a Ravager).

Note for helpers: raiders only fight the defending side. If you're friends with the attacker,
you can't hurt their raiders — unless it's *your own base* being raided, in which case you can
always defend it.

---

## Dying during a raid

Death drops everything you're carrying **except core items** — armor pieces, swords, the mace,
spears, bow, and crossbow. Each core item independently has an **80% chance to stay with you**
(in its exact slot, worn armor stays worn) and a 20% chance to drop like everything else. So
you'll usually keep your kit, but never count on it.

Full PvP death rules live in [`guide-pvp-mode.md`](guide-pvp-mode.md).

---

## Quick command reference

| Command | What it does |
|---|---|
| `/raid` | Open the raid target list |
| `/raid inv` | Open your personal raid loot inventory (withdraw-only) |
| `/HomeSystem turret deploy` | Place a turret at your feet (max 4, ≥22 blocks apart) |
| `/HomeSystem turret list` | List your turrets: level, HP, respawn timers |
| `/HomeSystem turret remove <#>` | Remove a turret (slot keeps its level) |
| `/HomeSystem turret upgrade` | Open the upgrade menu (64 Diamonds → L2, 1 Nether Star → L3) |
| `/HomeSystem turret attackmobs <on|off>` | Toggle whether your turrets also shoot hostile mobs |
