package dev.unrau.samsara.service;

import org.bukkit.Location;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The lifecycle of a placement held between a login and the join it becomes.
 *
 * <p>Every one of these is about something being handed out exactly once. A placement claimed twice
 * would put two players down from one search; a placement taken twice would write the same first join
 * into a record twice; and a placement never taken at all would accumulate for every login that was
 * refused after the handshake.
 */
class ArrivalQueueTest {

    private static final long ABANDONED_AFTER = 60_000L;

    private static ArrivalPreparation.Placement somewhere() {
        return new ArrivalPreparation.Placement(
            new Location(null, 120_000.5, 71, -98_000.5), ArrivalPreparation.Kind.FIRST_JOIN);
    }

    @Test
    @DisplayName("a prepared placement is claimed by the spawn position and then taken by the join")
    void travelsThroughBothStages() {
        ArrivalQueue queue = new ArrivalQueue(ABANDONED_AFTER);
        UUID player = UUID.randomUUID();
        ArrivalPreparation.Placement placement = somewhere();

        queue.prepared(player, placement);
        assertSame(placement, queue.claim(player), "the spawn position gets what was prepared");
        assertSame(placement, queue.take(player), "and the join that follows gets it too");
        assertEquals(0, queue.size(), "nothing is left behind once it has been recorded");
    }

    @Test
    @DisplayName("a placement can only be claimed once")
    void claimIsOneShot() {
        ArrivalQueue queue = new ArrivalQueue(ABANDONED_AFTER);
        UUID player = UUID.randomUUID();

        queue.prepared(player, somewhere());
        assertNotNull(queue.claim(player));
        assertNull(queue.claim(player), "a second spawn position must not be answered from one search");
    }

    @Test
    @DisplayName("a placement can only be taken once")
    void takeIsOneShot() {
        ArrivalQueue queue = new ArrivalQueue(ABANDONED_AFTER);
        UUID player = UUID.randomUUID();

        queue.prepared(player, somewhere());
        queue.claim(player);
        assertNotNull(queue.take(player));
        assertNull(queue.take(player), "one arrival is written down once");
    }

    @Test
    @DisplayName("a join with nothing prepared for it is answered with nothing")
    void unknownPlayersGetNothing() {
        ArrivalQueue queue = new ArrivalQueue(ABANDONED_AFTER);

        assertNull(queue.claim(UUID.randomUUID()));
        assertNull(queue.take(UUID.randomUUID()));
    }

    @Test
    @DisplayName("a placement cannot be taken without being claimed first")
    void takeDoesNotSeeUnclaimedPlacements() {
        ArrivalQueue queue = new ArrivalQueue(ABANDONED_AFTER);
        UUID player = UUID.randomUUID();

        queue.prepared(player, somewhere());
        assertNull(queue.take(player), "the join only ever sees what the spawn position acted on");
    }

    @Test
    @DisplayName("a login that never became a join is forgotten")
    void abandonedPreparationsExpire() {
        AtomicLong now = new AtomicLong(1_000_000L);
        ArrivalQueue queue = new ArrivalQueue(ABANDONED_AFTER, now::get);
        UUID refused = UUID.randomUUID();

        queue.prepared(refused, somewhere());
        now.addAndGet(ABANDONED_AFTER + 1);

        // Preparing for anybody is what sweeps the abandoned ones up; nothing else runs on a timer.
        queue.prepared(UUID.randomUUID(), somewhere());

        assertNull(queue.claim(refused), "the refused login's placement is gone");
        assertEquals(1, queue.size(), "and only the fresh one is left");
    }

    @Test
    @DisplayName("a claimed placement never expires — its join is one event away")
    void claimedPlacementsAreNotSweptUp() {
        AtomicLong now = new AtomicLong(1_000_000L);
        ArrivalQueue queue = new ArrivalQueue(ABANDONED_AFTER, now::get);
        UUID player = UUID.randomUUID();

        queue.prepared(player, somewhere());
        queue.claim(player);
        now.addAndGet(ABANDONED_AFTER * 10);
        queue.prepared(UUID.randomUUID(), somewhere());

        assertNotNull(queue.take(player), "a player already being added to the world keeps their record");
    }

    @Test
    @DisplayName("discarding drops a placement at either stage")
    void discardClearsBothStages() {
        ArrivalQueue queue = new ArrivalQueue(ABANDONED_AFTER);
        UUID waiting = UUID.randomUUID();
        UUID claimed = UUID.randomUUID();

        queue.prepared(waiting, somewhere());
        queue.prepared(claimed, somewhere());
        queue.claim(claimed);

        queue.discard(waiting);
        queue.discard(claimed);

        assertEquals(0, queue.size());
        assertNull(queue.claim(waiting));
        assertNull(queue.take(claimed));
    }

    @Test
    @DisplayName("an expiry that never expires is rejected rather than silently accepted")
    void expiryMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new ArrivalQueue(0));
        assertThrows(IllegalArgumentException.class, () -> new ArrivalQueue(-1));
    }
}
