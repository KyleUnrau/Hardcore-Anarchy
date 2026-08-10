package dev.unrau.samsara.service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Tracks where each player who is currently logging in is going to be put.
 *
 * <p>A placement is asked for by two different events in turn, so it moves through two stages rather
 * than sitting in one bucket. It is prepared on the login thread; the spawn-position event
 * {@link #claim}s it, which is the moment it becomes the position the player actually appears at; and
 * the join that follows {@link #take}s it, because by then the position alone no longer says what
 * kind of arrival it was — a life beginning, or an exile finally being honoured.
 *
 * <p>Both stages are one-shot. A placement claimed twice would mean two players placed from one
 * search, and a placement taken twice would write the same first join into a record twice.
 *
 * <p>Placements can also be prepared for a login that never becomes a join — a whitelist, a ban, a
 * connection that drops after the handshake — and nothing tells this class about those. Prepared
 * entries therefore expire; claimed ones do not, because a claim means the player is being added to
 * the world and their join is one event away.
 *
 * <p>Kept free of Bukkit, apart from the location it is carrying, so the lifecycle can be tested
 * directly — the same reasoning as {@link TransitRegistry}.
 */
final class ArrivalQueue {

    private record Prepared(ArrivalPreparation.Placement placement, long at) {}

    private final Map<UUID, Prepared> waiting = new ConcurrentHashMap<>();
    private final Map<UUID, ArrivalPreparation.Placement> arriving = new ConcurrentHashMap<>();

    private final long abandonedAfterMillis;
    private final LongSupplier clock;

    ArrivalQueue(long abandonedAfterMillis) {
        this(abandonedAfterMillis, System::currentTimeMillis);
    }

    ArrivalQueue(long abandonedAfterMillis, LongSupplier clock) {
        if (abandonedAfterMillis <= 0) {
            throw new IllegalArgumentException("abandonedAfterMillis must be positive, got "
                + abandonedAfterMillis);
        }
        this.abandonedAfterMillis = abandonedAfterMillis;
        this.clock = clock;
    }

    /** Records where a player who is logging in will be put, dropping anything abandoned first. */
    void prepared(UUID uuid, ArrivalPreparation.Placement placement) {
        forgetAbandoned();
        waiting.put(uuid, new Prepared(placement, clock.getAsLong()));
    }

    /**
     * Takes the placement to spawn this player at, moving it on to await their join.
     *
     * @return the placement, or null if nothing was prepared or it has already been claimed
     */
    ArrivalPreparation.Placement claim(UUID uuid) {
        Prepared prepared = waiting.remove(uuid);
        if (prepared == null) return null;

        arriving.put(uuid, prepared.placement());
        return prepared.placement();
    }

    /**
     * Takes the placement this player was let in on, so their join can record what it meant.
     *
     * @return the placement, or null if they were placed the ordinary way
     */
    ArrivalPreparation.Placement take(UUID uuid) {
        return arriving.remove(uuid);
    }

    /** Drops anything held for a player, at either stage. */
    void discard(UUID uuid) {
        waiting.remove(uuid);
        arriving.remove(uuid);
    }

    /** How many placements are held at either stage. Used by tests. */
    int size() {
        return waiting.size() + arriving.size();
    }

    /** Drops placements prepared for logins that never became joins. */
    private void forgetAbandoned() {
        long cutoff = clock.getAsLong() - abandonedAfterMillis;
        waiting.values().removeIf(prepared -> prepared.at() <= cutoff);
    }
}
