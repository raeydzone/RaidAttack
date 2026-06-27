package com.raeyd.raidattack.quest;

import java.util.Locale;

/**
 * The RaidAttack achievement set. Each quest has a short title (3–10 words) and a target count;
 * single-step quests use a target of 1 (rendered without a [x/y] counter). Progress is tracked
 * per-player by {@link QuestManager} and shown via {@code /ra quests}.
 */
public enum Quest {
    TURRETS_LVL2       ("Upgrade all 4 turrets to level 2", 4),
    TURRETS_LVL3       ("Upgrade all 4 turrets to level 3", 4),
    LONG_RAID_BONUS    ("Earn a 15-minute raid bonus",      1),
    PREVENT_RAID       ("Successfully defend a raid",       1),
    TURRET_KILL_PLAYER ("Turrets kill a hostile player",    1),
    TURRET_REGEN       ("Get healed by a turret blast",     1),
    START_RAIDS        ("Start 5 raids",                    5),
    STEAL_LOOT         ("Steal loot during a raid",         1),
    TURRET_KILL_WITHER ("Make a turret kill a wither",      1),
    RAIDER_KILLS       ("Turrets rack up 1,000 raider kills", 1000),
    DESTROY_TURRETS    ("Destroy 10 turrets as a raider",   10),
    DESTROY_TURRETS_ONE_RAID ("Destroy 10 turrets in one raid", 1);

    private final String title;
    private final int target;

    Quest(String title, int target) {
        this.title = title;
        this.target = target;
    }

    public String title()  { return title; }
    public int target()     { return target; }
    /** Stable lowercase id used as the YAML key. */
    public String key()     { return name().toLowerCase(Locale.ROOT); }
}
