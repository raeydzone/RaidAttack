# Home Protection (Claims)

Your claim is your home on Raid Attack: a square piece of the world that belongs to you and you alone. Inside it, only you and the friends you choose can build, break, open chests, or use doors — everyone else is locked out automatically, and even creepers, TNT, lava, and piston contraptions can't touch your blocks from outside. This guide covers everything about creating a claim, what the protection actually stops, managing your friends list, teleporting home, the public spawn area, and what happens when you unclaim. Raids and turrets are only mentioned in passing here — for the full rules on attacking and defending bases, see the **[raids & turrets guide](guide-raids-and-turrets.md)**.

---

## What is a claim?

- A claim is a **square zone** you own. It reaches from the bottom of the world to the sky — the full height, not just the surface.
- **Claiming is free.** There's no item or money cost — your only decision is where and how big.
- **Every player can own exactly one claim.** If you want to move, you unclaim first and claim somewhere new.
- Your claim also casts a shadow into the **Nether**: the Nether spot that lines up with your base (Nether coordinates are 1:8) is protected exactly like the base itself. Nobody can build a portal trap or a bombing platform "above" your base from the Nether side. The End is not mirrored.

## Creating a claim

Stand where you want one corner of your claim to be, face the direction you want it to extend, and run:

```
/HomeSystem claim <size>
```

(`/hs` and `/homeprotection` work as shortcuts for `/HomeSystem` everywhere.)

- **Size must be between 80 and 200.** The number is the side length in blocks, so `/HomeSystem claim 100` gives you a 100×100 zone.
- The block you're standing on becomes one corner; the zone extends **in the direction you're looking**.
- On success you'll hear a level-up chime and see a **green particle outline** trace your border for 10 seconds. You can replay the outline any time with `/HomeSystem show border` (5 seconds, no sound).

A claim will be **rejected** if:

- **It overlaps someone else's claim.** You'll be told whose claim is in the way — move or pick a smaller size.
- **It overlaps the public spawn area** (see below). The zone around world spawn is permanently claim-free.
- **You already have a claim.** Unclaim first.

### Checking your claim

- `/HomeSystem info` — your claim's size, its two corner coordinates, your friends list, and how many turrets you have deployed.
- `/HomeSystem current info` — information about whichever zone you're **standing in right now**: the owner, the size, whether it's friendly or hostile to you, and its turret count. Handy for scouting.

## What the protection actually does

When a player who isn't a member of your claim (not the owner, not on the friends list) steps inside, two things happen:

1. **They're switched to Adventure mode** for as long as they're inside, and see a red "Entering <name>'s hostile zone" message. Their normal mode comes back the moment they leave. (You and your friends see a green message instead and keep your normal mode.)
2. **A second layer of block protection** covers everything Adventure mode alone can't stop:

- **No breaking or placing blocks** — including reaching in from just outside the border.
- **No doors, buttons, or levers.** Outsiders can't open or trigger doors, trapdoors, fence gates, buttons, levers, pressure plates, or tripwires inside your claim — not by clicking, not by stepping on a plate, and not by **shooting an arrow** over your wall at a button or tripwire. Your own redstone keeps working normally for you.
- **No opening containers — ever.** Chests, trapped chests, barrels, hoppers, dispensers, droppers, furnaces, blast furnaces, smokers, brewing stands, and shulker boxes inside a claim can only be opened by the claim's members. This holds at all times, raid or no raid. (During a raid there's one extra rule that can temporarily seal chests even for members — see the [raids & turrets guide](guide-raids-and-turrets.md).)
- **Explosions can't damage your blocks.** Creepers, TNT, and every other explosion still make noise and can still hurt players and animals, but blocks inside a claim are never destroyed by them.
- **No fire griefing.** Outsiders can't ignite blocks in your zone.
- **No bucket tricks.** Outsiders can't pour lava or water into your claim, can't scoop fluids out of it, and fluid placed **outside** your border simply stops at the edge — it never flows in.
- **No cross-border pistons.** A piston outside your claim can't push blocks in, and no piston arrangement can move blocks across your border in either direction. Pistons fully inside your own claim work normally.
- **Mobs can't reshape your base.** Enderman block-stealing, ravager smashing, and similar mob-driven block changes are cancelled inside claims.
- **Gravity blocks are handled carefully.** Sand, gravel, concrete powder, and anvils fall normally **inside your own claim** — your builds behave like vanilla. But a falling block that comes flying in **from outside** your claim (sand cannons, drop towers hugging your border, and the like) is treated as hostile and simply deleted when it tries to land, leaving nothing behind.

If someone tries any of this, they get a short red chat warning telling them whose zone they're interfering with.

One thing to know as a visitor: stepping into someone's hostile zone marks you as an active enemy for their defenses for a short while — details in the **[raids & turrets guide](guide-raids-and-turrets.md)**.

## Friends

You can grant other players full member access to your claim. Members can build, break, open all containers, use all doors, and are treated exactly like you by the protection.

```
/HomeSystem add <player>       — add a friend (they must be online)
/HomeSystem remove <player>    — remove a friend (works even if they're offline)
```

- Added and removed players get a chat notice when they're online.
- **Alliance shortcut:** `/HomeSystem add self_alliance` adds every member of your current alliance to your claim in one command, and `/HomeSystem remove self_alliance` removes them all again. Players who joined your alliance later are not added retroactively — run it again if the roster grows.
- **The friends list is locked during a raid.** While you're involved in any active raid — whether your base is being attacked or you're the attacker — you cannot add or remove members. Wait until the raid ends.

Friends do **not** get access to your claim management: they can't add or remove other friends, can't unclaim, and can't manage your turrets. Those commands always act on the claim *you* own.

## Home teleport

From anywhere in the world you can channel a teleport back to your own claim:

```
/HomeSystem teleport        (also: /HomeSystem tp, /HomeSystem home)
```

- The channel takes **15 seconds**, shown as a countdown boss bar at the top of your screen.
- **Don't move** — shifting more than a fraction of a block from where you started cancels it.
- **Don't take damage** — *any* damage during the channel cancels it.
- **No escaping PvP:** if another player has damaged you in the last **90 seconds**, you can't start the channel at all; the command tells you how long to wait. Being hit by raid mobs doesn't count against this timer — only real players do — so you can still teleport away from a monster fight.
- You can't use it while you're already inside your home, and you need to actually own a claim.
- On completion you're placed at a **random safe surface spot** somewhere inside your claim (solid, dry ground with headroom) — not a fixed point, so nobody can camp your arrival.

## The public spawn area

A large safe square — about **500×500 blocks** (250 blocks in every direction from the world spawn point) — surrounds spawn. It's lighter than a claim but has firm rules:

- **Nobody can claim here.** Any claim that would overlap the spawn area is rejected.
- **Nobody can build here.** Everyone inside the spawn area is held in Adventure mode, so no placing or breaking blocks.
- **The terrain is explosion-proof.** Creeper and TNT block damage is cancelled inside the area.
- **Random respawns.** If you die without a bed or respawn anchor, you don't reappear on the exact spawn block — you're scattered to a random safe surface spot somewhere inside the area, so spawn-campers can't predict where you'll appear. Bed and anchor respawns are untouched.
- You'll see an "Entering the spawn area" / "Leaving the spawn area" message as you cross its edge.

Note this is terrain protection only — it's not a full claim. Fluids, entities, and containers inside the spawn area behave like normal Minecraft.

## Turrets (in brief)

Every claim can host up to **4 defensive turrets**, deployed inside your own border with `/HomeSystem turret deploy`. Turret combat, upgrades, targeting, and all raid interactions are covered in the **[raids & turrets guide](guide-raids-and-turrets.md)** — this guide only needs you to know they exist and that unclaiming treats them specially (next section).

## Unclaiming

```
/HomeSystem unclaim
```

This deletes your claim immediately. Know exactly what you keep and what you lose:

**What is kept:**

- **Your turret upgrade levels are remembered.** Each of your 4 turret slots keeps its upgrade level in storage, and the next time you create a claim — anywhere, any size — your new claim's slots start at those saved levels. Your upgrade investment is never wasted by moving house.

**What you lose:**

- **The protection, instantly.** The moment you unclaim, the area is ordinary wilderness — anyone can build, break, and open anything there. Your blocks and chest contents stay in the world physically, but nothing guards them.
- **Your deployed turrets.** The turret structures are removed from the world when you unclaim (only the slot levels are kept, as above). You'll deploy fresh turrets at your new base.
- **Any active re-raid protection.** After your base is raided, it normally can't be raided again for **36 hours** — but that shield is tied to your base staying put. Unclaiming (moving your base away) **forfeits the remaining protection immediately**; a fresh claim starts with no shield. See the [raids & turrets guide](guide-raids-and-turrets.md) for how raid protection works.

So: unclaim when you genuinely want to relocate — not as a trick to dodge a raid, because the one thing you'd want to carry with you is exactly the thing that doesn't come along.

---

## Quick command reference

| Command | What it does |
|---|---|
| `/HomeSystem claim <80–200>` | Create your square claim, extending the way you're facing |
| `/HomeSystem info` | Your claim's size, corners, friends, turret count |
| `/HomeSystem current info` | Info about the zone you're standing in |
| `/HomeSystem show border` | Replay the green particle border outline |
| `/HomeSystem add <player>` | Add a friend (must be online); `self_alliance` adds your whole alliance |
| `/HomeSystem remove <player>` | Remove a friend; `self_alliance` removes your whole alliance |
| `/HomeSystem teleport` | 15-second channel, then warp to a random safe spot in your claim |
| `/HomeSystem unclaim` | Delete your claim (turret levels kept; re-raid protection forfeited) |

Aliases: `/hs` and `/homeprotection` both work in place of `/HomeSystem`.
