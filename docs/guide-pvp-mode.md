# PvP Mode, Tactical Leaving & Core-Item Protection — Player Guide

This guide explains exactly how PvP mode works on RaidAttack, what happens if you log out
during a fight ("tactical leaving"), and which items you keep when you die. Read it once and
you'll never be surprised.

---

## PvP Mode

### What is it?

PvP mode is the server saying: **you two are in a real fight now.** It exists between *pairs*
of players — you and one specific opponent. It is not a global "combat on" switch.

### How do you enter it?

You and another player enter PvP mode the moment **one of you has dealt more than 25 raw
damage to the other within the last 5 minutes**.

- **Raw damage** means damage *before* armor, Protection enchantments, or potion effects
  reduce it. Tanking hits in netherite doesn't protect you from the counter — what matters is
  how hard the weapon hit, not how much health you actually lost. (25 raw damage is 12.5
  hearts' worth of raw hits.)
- The 5 minutes are a **rolling window**: old hits fade out of the count as they pass the
  5-minute mark.
- Damage in **one direction is enough**. If someone attacks you and crosses the threshold, you
  are pulled into PvP mode too, instantly — even if you never fought back.
- Melee hits and projectiles (arrows, thrown weapons, etc.) all count. Raider NPCs never
  count — this is strictly player vs. player.

When a pair enters PvP mode, **one server-wide broadcast** announces it:

> ⚔ *PlayerA and PlayerB entered PvP mode.*

### Can I be in PvP mode with several people?

Yes. Each opponent is a separate, independent pair. You can be in PvP mode with two, three, or
more players at once — each fight tracks its own damage and ends on its own.

### How does it end?

Your PvP mode with a specific opponent ends (checked every second) as soon as **any** of these
happens:

1. **The damage ages out.** Neither of you has more than 25 raw damage on the other inside
   the last 5 minutes anymore. Stop fighting, and roughly 5 minutes after the hits that
   crossed the threshold, the mode expires on its own.
2. **You separate.** You move more than **250 blocks apart on any axis** (X, Y, or Z), or end
   up in different worlds. Note it's a 250-block *cube*, not a sphere — going 251 blocks
   straight up counts too.
3. **Either of you dies** — for *any* reason. Fall damage, lava, a third player, a raider, or
   the tactical-leave execution described below. The survivor is released immediately.

When it ends, both players get a quiet personal message:

> *PvP mode with \<opponent\> ended — you may log out safely.*

That message is your green light. **If you haven't seen it (or the tactical-leave "flag
lifted" message), do not log out.**

One more detail: when a pair's PvP mode ends, its damage history is wiped. Re-entering PvP
mode with the same person requires a fresh 25+ raw damage — old hits never linger.

---

## Tactical Leaving (Combat Logging)

### The rule

**Logging out while in PvP mode — with anyone — is tactical leaving.** The server does not
kick you back in or kill you on the spot. Instead:

1. A **server-wide broadcast** announces that you tactically left, naming your opponent and
   how long you have to return.
2. Your **entire inventory is snapshotted** at the exact spot where you quit. This snapshot is
   saved to the database — a server restart grants no amnesty.
3. Your **10-minute offline countdown** starts.

### The 10-minute offline budget

You have **10 minutes of total offline time** to come back. This budget is **cumulative**, not
a simple timer you can reset:

- **Rejoining pauses the clock — it does NOT pardon you.** Log back in and the countdown
  freezes, but the flag stays until your PvP mode actually ends.
- **Leaving again resumes the clock where it stopped.** Quit with 6 minutes already burned,
  and your next absence starts with only 4 minutes left. Hopping in and out cannot stall the
  verdict.
- The budget only refills in two cases:
  - your **flag clears** (PvP mode over, or you died) — the next flag, if ever, starts fresh
    with a full 10 minutes;
  - you stay **15 continuous minutes back online while still flagged** — then the full
    10-minute budget is restored. This exists so a crash during a long fight doesn't
    permanently eat your safety margin.
- Server downtime counts as offline time. The flag lives in the database, so restarting the
  server doesn't stop your clock.

### Discord warnings

Every RaidAttack account is registered through Discord, so the **rAI bot** knows exactly who
you are. While you're flagged and offline, it DMs you:

- at **5 minutes** offline — a green embed reminding you to get back, with the server IP
  (`159.195.138.242:25565`) and the exact time you quit;
- at **8 minutes** offline — a second, distinctly more urgent warning.

If you get one of these DMs, join the server *now*. Joining pauses the clock the moment you
complete `/login`.

### What happens when you come back in time

The moment you finish `/login`:

- Your offline clock **pauses**.
- Your PvP pairs **unfreeze exactly where they left off**. While you were gone, the pair was
  frozen: the 5-minute damage window didn't tick down and the range wasn't checked. Your
  absence bought you nothing — the fight resumes as if you never left.
- The **250-block range is re-checked instantly.** If your opponent wandered off (or died)
  while you were away, your PvP mode ends right there, your flag lifts, and you're told:

  > *Your PvP mode is over — the tactical-leave flag was lifted. You may log out safely.*

- If PvP mode is **still active**, you stay flagged and get a red warning telling you how much
  return time you'd have left if you quit again. Stay online until the fight ends — you'll get
  the "flag lifted" message when it does.

Dying (for any reason) while flagged also settles everything: you died for real, your items
dropped normally, the flag clears.

### What happens if your budget runs out

If your cumulative offline time hits 10 minutes, the verdict executes — you die in absentia:

1. **Your entire snapshotted inventory drops at the exact spot where you logged out.** Anyone
   can walk over and take it.
2. A **server-wide death broadcast** goes out with your name, your opponent, and the
   **coordinates** of the drop:

   > ☠ *\<you\> got killed for tactical leaving — PvP mode against \<opponent\> at X, Y, Z*

3. The execution **counts as a death**: every PvP pair you were in ends and your opponents are
   released — they can log out safely.
4. **The next time you log in**, your inventory is wiped (those items already dropped at your
   quit spot — nothing duplicates) and you die an artificial, chat-silent death, respawning at
   your **bed / respawn anchor**, or the world spawn if you have none. You do not wake up
   standing at the death spot.

Edge case: if you make it back and authenticate at the very last second, you're killed **live**
on the quit spot instead — same broadcast, but since it's a real in-game death, the core-item
protection below applies to it.

**Bottom line:** never log out during a fight. If you must go, either finish the fight, get
250+ blocks away, or wait for the "you may log out safely" message.

---

## Core-Item Death Protection

Dying on RaidAttack drops your inventory — **except your core combat kit, which usually stays
with you.**

### What counts as a core item?

- **Every armor piece:** helmet, chestplate, leggings, boots (any material/tier).
- **Any sword** (any tier).
- **The mace.**
- **Any spear** (any tier).
- **Bow** and **crossbow**.

Everything else — blocks, food, tools, potions, shields, arrows, resources — drops normally.

### The 80/20 roll

On **any** death, each core item you're carrying rolls **independently**:

- **80% chance: it stays with you.** It remains in the *exact slot* it was in — worn armor is
  still worn when you respawn.
- **20% chance: it drops** on the ground like everything else.

Because every piece rolls on its own, partial outcomes are normal. Example: you die in full
armor with a sword and a bow — you might respawn with the chestplate, leggings, boots, sword,
and bow all intact, but the helmet rolled its 20% and is lying at your death spot. Usually you
keep most of your kit; you're never guaranteed all of it.

### When does this apply?

- **Every player death** — PvP, mobs, fall damage, lava, and even the *live* tactical-leave
  execution (if you got back just before the timer expired).
- **It does NOT apply to the offline tactical-leave execution.** If your 10-minute budget ran
  out while you were gone, the **entire snapshot drops — no rolls, no mercy.** That's the
  punishment. One more reason not to combat-log.

---

## Quick FAQ

**How do I know I'm in PvP mode?**
You saw the server-wide "⚔ … entered PvP mode" broadcast with your name in it, and you haven't
yet received the "PvP mode ended — you may log out safely" message.

**I never fought back — am I still in PvP mode?**
Yes, if your attacker dealt more than 25 raw damage to you within 5 minutes. Being attacked is
enough. Kill them, outrun them past 250 blocks, or survive until their damage ages out.

**Can I just run away instead of logging out?**
Yes — that's the legitimate escape. Get more than 250 blocks away and PvP mode ends on its own,
with the safe-logout message to confirm it.

**Does a server restart save me from the tactical-leave timer?**
No. The flag and your inventory snapshot are stored in the database, and downtime counts as
offline time.

**My game crashed mid-fight — am I punished?**
The server can't tell a crash from a combat log, so yes, you're flagged. But you have a full
10 minutes to reconnect, Discord DMs warn you at 5 and 8 minutes, rejoining pauses the clock,
and 15 continuous minutes back online refreshes the full budget. A crash you react to costs you
nothing.

**If I die normally, do I lose everything?**
No — each armor piece, sword, mace, spear, bow, and crossbow has an 80% chance to stay with
you, each rolled separately. Everything else drops.
