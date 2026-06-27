package com.raeyd.raidattack.alliance;

import com.raeyd.raidattack.core.ChatListener;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * One alliance: a named, colored group with a leader, members, and a queue of join requests
 * waiting on leader approval. Names are 1..{@link #MAX_NAME_LENGTH} chars and unique server-wide
 * (case-insensitive). The colour is one of the 16 vanilla {@link NamedTextColor} values — the
 * spec said "any colour in MC" and these are the named ones MC actually displays.
 *
 * <p>Identity convention: the canonical lookup key in {@link AllianceManager} is
 * {@code name.toLowerCase(Locale.ROOT)}; the {@link #getName()} return is the original casing
 * for display. Rename rewrites the index atomically.
 *
 * <p>Mutability: all setters here only mutate the field they name. Persistence + name-index
 * upkeep is the manager's responsibility — call sites must go through {@link AllianceManager}
 * for create / rename / disband, not poke fields directly.
 */
public final class Alliance {

    /** Max characters in an alliance display name. */
    public static final int MAX_NAME_LENGTH = 8;

    /** Stable DB primary key (world.alliances.id). 0 until persisted. Survives renames, so member
     *  and join-request rows reference this rather than the (mutable) name. */
    private long id;

    private String name;
    private NamedTextColor color;
    private UUID leader;
    /** Member set. A {@link CopyOnWriteArraySet} (not LinkedHashSet) because the async chat
     *  renderer reads/copies this set off the main thread ({@code ChatListener.narrowToAlliance})
     *  while the main thread mutates it via join/leave/kick — COW gives crash-free iteration and
     *  still preserves insertion order for {@code /alliance info}. Mutations are rare, so the
     *  copy-on-write cost is negligible. */
    private final Set<UUID> members;
    private final Set<UUID> pendingRequests;
    private final long createdAtMillis;

    /** Create a brand-new alliance with the leader auto-added as a member. */
    public Alliance(String name, NamedTextColor color, UUID leader, long createdAtMillis) {
        this.name = name;
        this.color = color;
        this.leader = leader;
        this.members = new CopyOnWriteArraySet<>();
        this.pendingRequests = new LinkedHashSet<>();
        this.members.add(leader);
        this.createdAtMillis = createdAtMillis;
    }

    /** Load constructor for persistence — caller supplies the full state. */
    public Alliance(String name, NamedTextColor color, UUID leader,
                    Set<UUID> members, Set<UUID> pendingRequests, long createdAtMillis) {
        this.name = name;
        this.color = color;
        this.leader = leader;
        this.members = new CopyOnWriteArraySet<>(members);
        this.pendingRequests = new LinkedHashSet<>(pendingRequests);
        if (!this.members.contains(leader)) this.members.add(leader);   // defensive
        this.createdAtMillis = createdAtMillis;
    }

    public long getId()                        { return id; }
    public void setId(long id)                 { this.id = id; }

    public String getName()                    { return name; }
    void setName(String name)                  { this.name = name; }

    public NamedTextColor getColor()           { return color; }
    public void setColor(NamedTextColor color) { this.color = color; }

    public UUID getLeader()                    { return leader; }
    public void setLeader(UUID leader)         { this.leader = leader; }

    /** Live, mutable member set including the leader. */
    public Set<UUID> getMembers()              { return members; }

    /** Live, mutable set of UUIDs whose join request is waiting on the leader. */
    public Set<UUID> getPendingRequests()      { return pendingRequests; }

    public long getCreatedAtMillis()           { return createdAtMillis; }

    public boolean isMember(UUID id) { return members.contains(id); }
    public boolean isLeader(UUID id) { return leader.equals(id); }
}
