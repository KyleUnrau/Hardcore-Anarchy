package dev.unrau.samsara.service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Tracks which players have a journey in flight.
 *
 * <p>A journey spans several ticks — chunks load, a site is built, a teleport completes — and the
 * portal or gateway that started it keeps firing the whole time. This registry is what stops those
 * repeats from stacking up into parallel journeys.
 *
 * <p>Claims carry a timestamp and expire. A journey that somehow never reports back must not lock a
 * player out of their own portal for the rest of the session, because a swallowed End portal entry
 * is still a cancelled one: the player falls through the portal block instead of travelling.
 *
 * <p>Kept free of Bukkit so the claim lifecycle can be tested directly.
 */
public final class TransitRegistry {

    /** What happened when a player tried to start a journey. */
    public enum Claim {
        /** The player was free; the journey may proceed. */
        CLAIMED,
        /** A journey is already under way; this attempt is a duplicate and should be swallowed. */
        BUSY,
        /** A previous claim was abandoned and has been taken over. Worth logging. */
        RECLAIMED_STALE
    }

    private final Map<UUID, Long> inFlight = new ConcurrentHashMap<>();
    private final long timeoutMillis;
    private final LongSupplier clock;

    public TransitRegistry(long timeoutMillis) {
        this(timeoutMillis, System::currentTimeMillis);
    }

    public TransitRegistry(long timeoutMillis, LongSupplier clock) {
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("timeoutMillis must be positive, got " + timeoutMillis);
        }
        this.timeoutMillis = timeoutMillis;
        this.clock = clock;
    }

    /** Attempts to claim a player for a journey. */
    public Claim begin(UUID uuid) {
        long now = clock.getAsLong();
        Long existing = inFlight.get(uuid);

        if (existing != null && now - existing < timeoutMillis) {
            return Claim.BUSY;
        }

        inFlight.put(uuid, now);
        return existing == null ? Claim.CLAIMED : Claim.RECLAIMED_STALE;
    }

    /** Releases a player's claim. Safe to call for a player who holds none. */
    public void end(UUID uuid) {
        inFlight.remove(uuid);
    }

    /** True while a player holds an unexpired claim. */
    public boolean isInFlight(UUID uuid) {
        Long existing = inFlight.get(uuid);
        return existing != null && clock.getAsLong() - existing < timeoutMillis;
    }

    /** Number of claims currently held, expired or not. Used by admin output and tests. */
    public int size() {
        return inFlight.size();
    }
}
