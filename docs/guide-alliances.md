# Alliances

An alliance is a named, colored team of players with one leader. Once you're in one, the alliance tag — `[Name]` in the alliance's color — shows above your head, in the tab list, and in front of your chat messages; you and your allies can no longer hurt each other in PvP; you get a private alliance-only chat channel; and the whole alliance counts as one side in raids — allies defend your base with you, and you can never raid each other. The player who creates the alliance is its leader and controls invitations, kicks, the name, the color, and whether the alliance exists at all.

---

## Quick command reference

| Command | Who can use it | What it does |
|---|---|---|
| `/alliance create <name> <color>` | Anyone not in an alliance | Found a new alliance; you become its leader |
| `/alliance join <name>` | Anyone not in an alliance | Send a join request to that alliance |
| `/alliance accept <player>` | Leader only | Accept a pending join request |
| `/alliance reject <player>` | Leader only | Decline a pending join request |
| `/alliance pending_accepts` | Any member | List the pending join requests |
| `/alliance leave` | Members (not the leader) | Leave your alliance |
| `/alliance kick <player>` | Leader only | Remove a member |
| `/alliance change_name <newname>` | Leader only | Rename the alliance |
| `/alliance color <color>` | Leader only | Change the alliance color |
| `/alliance info [name]` | Anyone | Show leader + member list of your (or any) alliance |
| `/alliance list` | Anyone | List every alliance on the server with member counts |
| `/alliance destruct` | Leader only | Disband the alliance permanently |
| `/a` | Any member | Toggle alliance-only chat on/off |
| `/a <message>` | Any member | Send a single message to your alliance only |

Several subcommands have aliases that do the same thing: `destruct` = `disband`, `color` = `colour`, `change_name` = `rename`, `pending_accepts` = `pending`, `reject` = `deny`. Typing `/alliance` with nothing after it shows the in-game help, and tab completion works on every subcommand (alliance names, colors, pending applicants, kickable members).

---

## Creating an alliance

```
/alliance create <name> <color>
```

You become the leader and the first member. Creating an alliance is free — no cost, no cooldown. The founding of a new alliance is announced to the entire server.

**You can only be in one alliance at a time.** If you're already in one, you must leave it (or disband it, if you're the leader) before creating a new one.

### Name rules

- **1 to 8 characters.**
- Only **letters, digits, and underscores** (`A–Z`, `a–z`, `0–9`, `_`). No spaces, no color codes, no other special characters.
- **Unique server-wide**, ignoring capitalization — if `Wolves` exists, nobody can create `wolves` or `WOLVES`.
- The capitalization you type is the capitalization everyone sees.

### Colors

The color tints your alliance tag everywhere it appears (nameplate, tab list, chat, announcements). Pick one of the 16 Minecraft colors:

`black`, `dark_blue`, `dark_green`, `dark_aqua`, `dark_red`, `dark_purple`, `gold`, `gray`, `dark_gray`, `blue`, `green`, `aqua`, `red`, `light_purple`, `yellow`, `white`

Color input is forgiving: names are not case-sensitive, you can drop the underscore (`darkblue`, `lightpurple`), `grey` works for `gray`, and `pink` is accepted as `light_purple`.

Example:

```
/alliance create Wolves dark_red
```

---

## Joining an alliance

Joining is a two-step handshake: you **request**, the leader **accepts**.

1. **You request:** `/alliance join <name>`
   - You must not already be in an alliance.
   - Every online member of that alliance is notified that you asked to join (not just the leader).
   - You can't send a second request to the same alliance while one is already pending, but you *can* have requests pending at several different alliances at once.
2. **The leader accepts or rejects:**
   - `/alliance accept <player>` — you become a member immediately and get a message telling you so.
   - `/alliance reject <player>` — your request is quietly removed. You are **not** notified of a rejection.

If you join some *other* alliance while a request is still pending, that request can no longer be accepted — the leader is simply told you joined a different alliance in the meantime.

Any member can check the queue with `/alliance pending_accepts`, but only the leader can act on it.

There is **no member limit** — an alliance can grow as large as you like.

---

## What being allied actually changes

### Your name tag

Every member gets a `[Name]` tag in the alliance's color, shown **above their head**, in the **player tab list**, and in **chat**. Your own username stays white — only the tag wears the color.

### PvP protection between allies

**Allies cannot damage each other.** This covers direct melee hits and projectiles — arrows, crossbow bolts, tridents, snowballs, eggs, and so on. The protection is automatic and cannot be turned off; the only way to fight an ally is for one of you to leave the alliance first.

One honest caveat: indirect damage that the game can't trace back to a specific player (for example, a TNT explosion) may still hurt allies. Don't set off explosives on top of your teammates.

### Alliance chat — `/a`

Two ways to use it (both require being in an alliance):

- **`/a <message>`** — sends that single message to your alliance only, then you're back in normal chat.
- **`/a`** (nothing after it) — toggles **alliance-only mode**. While it's on, *everything* you type in chat goes only to your alliance. Run `/a` again to go back to global chat.

Alliance-only messages are shown in gray with an `[Alliance-Only Chat]` label so you always know which channel you're in, and they are truly private — players outside the alliance don't receive them at all (the server console still logs them). The toggle resets when you log out; after a relog you're always back in global chat.

Chat line formats you'll see:

```
Username: message                              (no alliance)
[Tag] Username: message                        (alliance member, global chat)
[Alliance-Only Chat] [Tag] Username: message   (alliance-only, gray, members only)
```

### Claims and turrets — allies are NOT automatically trusted

This one matters: **being in an alliance does not automatically make you a friend of your allies' claims.** A claim's turrets keep shooting alliance members, and allies stay subject to the claim's protections, until the claim owner adds them to the claim's friends list.

To do that in one command, the claim system has a shortcut keyword:

```
/HomeSystem add self_alliance
```

That adds **every current member of your alliance** to your claim's friends list at once (skipping anyone already on it). `/hs` works as a shorthand for `/HomeSystem`. The reverse also exists:

```
/HomeSystem remove self_alliance
```

which drops all of your alliance members from the friends list again.

Note that `self_alliance` is a snapshot, not a subscription — players who join the alliance *later* are not friended automatically. Run `/HomeSystem add self_alliance` again after new members join if you want them covered.

### Raids — the alliance is a side

Alliances are woven deeply into the raid system:

- **You can't raid an ally.** A claim whose owner is in your alliance shows as friendly (a flower) in the `/raid` target list and can't be targeted — the attempt is refused even if you try. The same goes for anyone on your own claim's friends list.
- **Defending together.** When an ally's base is raided, every member of the owner's alliance counts as a **defender**: you see the raid boss bar, you hear the raid horns, you get the raid alerts, and you can freely fight the raiders attacking the base.
- **Attacking together.** When an ally *starts* a raid, the whole alliance is on the attacker's side: the raid monsters will not target you, and you can't damage them either (no sabotaging your own raid). If someone is somehow on both sides at once, defending the raided base always wins — they can still fight the raiders.
- **Raid rewards can reach allies — if they participate.** On defense, an allied member must personally kill at least 10 raiders (and actually spend time inside the zone) to qualify for the defenders' end-of-raid bonuses; the claim owner qualifies unconditionally. On attack, allied members share in attacker-side bonuses only if they're inside the raided zone and have dealt real damage to the defenders during the raid. Allies also get their side's loot-steal notifications.
- **Membership is frozen during a raid.** While you're involved in an active raid — as the defender whose zone is under attack, or as an attacker with a raid in flight — you can't create, join, leave, or disband an alliance until it ends. Leaders also can't accept a raid-locked applicant or kick a raid-locked member. This stops anyone from switching sides mid-fight. (Changing the alliance's name or color is still allowed.)

---

## Managing your alliance (leader tools)

All of these are **leader-only**:

- **`/alliance kick <player>`** — removes a member instantly. Their tag disappears and they lose all alliance benefits. The kicked player is told, if they're online. The leader can't be kicked.
- **`/alliance change_name <newname>`** — renames the alliance. Same rules as at creation (1–8 characters, letters/digits/underscores, must not collide with another alliance's name). Everyone's tag updates immediately. Changing only the capitalization of the current name (`wolves` → `Wolves`) is allowed.
- **`/alliance color <color>`** — changes the color; same 16 options as at creation. Takes effect immediately for all members.
- **`/alliance accept` / `/alliance reject`** — see [Joining an alliance](#joining-an-alliance) above.

There is **no leadership transfer.** The player who created the alliance is its leader for as long as the alliance exists.

## Leaving

- **Members:** `/alliance leave` — you're out immediately: tag gone, benefits gone. No confirmation prompt, so be sure.
- **The leader cannot leave.** If you're the leader and want out, your only option is to disband.

Remember: leaving (like joining) is blocked while you're tied up in an active raid.

## Disbanding — `/alliance destruct`

Leader-only, and permanent. `/alliance destruct` deletes the alliance outright:

- Every member is released and loses their tag immediately.
- All pending join requests are discarded.
- The name becomes available again for anyone to claim.

There is no confirmation step and no undo — the command executes the moment you send it.

---

## Looking things up

- **`/alliance info`** — shows your own alliance: its tag, the leader, and the full member list (the leader is marked).
- **`/alliance info <name>`** — the same, for any alliance on the server. Anyone can inspect any alliance, member or not.
- **`/alliance list`** — every alliance on the server with its colored tag and member count.

---

## Frequently asked questions

**Does creating or running an alliance cost anything?**
No. Creation, renaming, recoloring — all free.

**Is there a member cap?**
No.

**Can I be in two alliances?**
No. One alliance per player, always.

**Can the leader hand leadership to someone else?**
No. There is no transfer command — if the leader wants out, the only option is `/alliance destruct`.

**I left / got kicked — do I keep anything?**
No. PvP protection, the tag, alliance chat, and raid-side membership all end the instant your membership does.

**Why is my own turret shooting my ally?**
Alliance membership doesn't extend to claims automatically. Run `/HomeSystem add self_alliance` on your claim to friend your whole alliance in one go.

**Why can't I join/leave right now?**
You (or the player involved) are part of an active raid. Alliance membership is locked until the raid ends.
