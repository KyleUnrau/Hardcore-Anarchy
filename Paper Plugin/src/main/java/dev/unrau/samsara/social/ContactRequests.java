package dev.unrau.samsara.social;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Contact requests that have been sent and not yet answered.
 *
 * <p>Held in memory and not on disk, which is a decision rather than a shortcut. A request is a
 * question asked of somebody who is standing there: it is offered while both players are online, it
 * expires on its own, and a restart is allowed to forget it. What must survive a restart is the
 * <em>answer</em> — and the answer is a contact, which is written to both records the moment it is
 * given.
 *
 * <p>The declined table is the other half of consent. Without it, "no" costs one keystroke to give
 * and nothing at all to ignore, and a request can simply be sent again every few seconds. A decline
 * closes the door for a while, and says so.
 */
public class ContactRequests {

    /** A pending request, and the moment it stops being one. */
    private record Pending(UUID from, String fromName, long expiresAtMillis) {}

    /** Requests waiting on an answer, keyed by who has to give it. */
    private final Map<UUID, Map<UUID, Pending>> incoming = new ConcurrentHashMap<>();

    /** Pairs where the last thing that happened was a refusal, and when it stops counting. */
    private final Map<PairKey, Long> declinedUntil = new ConcurrentHashMap<>();

    private final LongSupplier clock;

    public ContactRequests() {
        this(System::currentTimeMillis);
    }

    /** Direct constructor, used by tests that need to move time by hand. */
    public ContactRequests(LongSupplier clock) {
        this.clock = clock;
    }

    /** What stopped a request from being sent, or {@link #SENT} when nothing did. */
    public enum SendOutcome {
        SENT,
        /** They had already asked us; sending back is the same as accepting, and does. */
        RECIPROCATED,
        ALREADY_PENDING,
        RECENTLY_DECLINED
    }

    /**
     * Records a request from one player to another.
     *
     * <p>A request sent to somebody who has already asked you is not a second request; it is an
     * answer to the first, and is reported as such so the caller links them instead.
     */
    public SendOutcome send(UUID from, String fromName, UUID to, int expirySeconds,
                            int declineCooldownSeconds) {
        long now = clock.getAsLong();
        sweep(now);

        if (declineCooldownSeconds > 0) {
            Long until = declinedUntil.get(PairKey.of(from, to));
            if (until != null && until > now) {
                return SendOutcome.RECENTLY_DECLINED;
            }
        }

        if (pending(from, to) != null) {
            return SendOutcome.RECIPROCATED;
        }
        if (pending(to, from) != null) {
            return SendOutcome.ALREADY_PENDING;
        }

        incoming.computeIfAbsent(to, key -> new ConcurrentHashMap<>())
            .put(from, new Pending(from, fromName, now + expirySeconds * 1000L));
        return SendOutcome.SENT;
    }

    /** Whether {@code to} has an unanswered request from {@code from}. */
    public boolean hasPending(UUID to, UUID from) {
        return pending(to, from) != null;
    }

    /** Removes a pending request and reports whether there was one. Used by accept and decline. */
    public boolean take(UUID to, UUID from) {
        Map<UUID, Pending> mine = incoming.get(to);
        if (mine == null) return false;

        Pending removed = mine.remove(from);
        if (mine.isEmpty()) incoming.remove(to);
        return removed != null && removed.expiresAtMillis() > clock.getAsLong();
    }

    /** Records a refusal, closing the door on both directions for a while. */
    public void decline(UUID to, UUID from, int cooldownSeconds) {
        take(to, from);
        // Both directions: a refusal that only stopped the original asker would be worked around by
        // asking the other way about.
        take(from, to);
        if (cooldownSeconds > 0) {
            declinedUntil.put(PairKey.of(to, from), clock.getAsLong() + cooldownSeconds * 1000L);
        }
    }

    /** Forgets any refusal between these two — a contact made anyway settles the question. */
    public void clearDecline(UUID a, UUID b) {
        declinedUntil.remove(PairKey.of(a, b));
    }

    /** Who is waiting on an answer from this player, most recent last. */
    public List<UUID> pendingFor(UUID to) {
        sweep(clock.getAsLong());
        Map<UUID, Pending> mine = incoming.get(to);
        return mine == null ? List.of() : new ArrayList<>(mine.keySet());
    }

    /** Who this player is waiting on an answer from. */
    public List<UUID> sentBy(UUID from) {
        sweep(clock.getAsLong());
        List<UUID> targets = new ArrayList<>();
        incoming.forEach((to, requests) -> {
            if (requests.containsKey(from)) targets.add(to);
        });
        return targets;
    }

    /** Drops everything either player is party to. Used when one of them ignores the other. */
    public void forget(UUID a, UUID b) {
        take(a, b);
        take(b, a);
    }

    private Pending pending(UUID to, UUID from) {
        Map<UUID, Pending> mine = incoming.get(to);
        if (mine == null) return null;

        Pending request = mine.get(from);
        if (request == null) return null;
        if (request.expiresAtMillis() <= clock.getAsLong()) {
            mine.remove(from);
            return null;
        }
        return request;
    }

    /** Drops what has run out. Cheap, and run on every question rather than on a timer. */
    private void sweep(long now) {
        incoming.values().forEach(requests ->
            requests.values().removeIf(request -> request.expiresAtMillis() <= now));
        incoming.values().removeIf(Map::isEmpty);
        declinedUntil.values().removeIf(until -> until <= now);
    }
}
