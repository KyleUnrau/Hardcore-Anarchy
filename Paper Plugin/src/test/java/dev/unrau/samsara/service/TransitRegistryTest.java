package dev.unrau.samsara.service;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A claim that is never released is not a harmless leak: a swallowed End portal entry is still a
 * cancelled one, so the player falls through the portal block instead of travelling — into the lava
 * under a stronghold portal room. These tests pin the whole claim lifecycle.
 */
class TransitRegistryTest {

    private static final long TIMEOUT = 15_000L;

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final TransitRegistry registry = new TransitRegistry(TIMEOUT, clock::get);
    private final UUID traveller = UUID.randomUUID();

    @Test
    void aFreePlayerIsClaimed() {
        assertEquals(TransitRegistry.Claim.CLAIMED, registry.begin(traveller));
        assertTrue(registry.isInFlight(traveller));
    }

    @Test
    void repeatPortalTicksAreSwallowedWhileAJourneyRuns() {
        registry.begin(traveller);

        for (int tick = 0; tick < 20; tick++) {
            clock.addAndGet(50);
            assertEquals(TransitRegistry.Claim.BUSY, registry.begin(traveller),
                "a duplicate portal tick must not start a second journey");
        }
    }

    @Test
    void endingAJourneyReleasesThePlayerImmediately() {
        registry.begin(traveller);
        registry.end(traveller);

        assertFalse(registry.isInFlight(traveller));
        assertEquals(0, registry.size(), "a finished journey must leave nothing behind");
        // The next portal entry is a clean claim, not a stale reclaim.
        assertEquals(TransitRegistry.Claim.CLAIMED, registry.begin(traveller));
    }

    @Test
    void aPlayerIsNeverLockedOutOfTheirOwnPortalForever() {
        // A journey that never reports back — the failure mode that stranded players in a portal
        // and dropped them into lava.
        registry.begin(traveller);

        clock.addAndGet(TIMEOUT);

        assertFalse(registry.isInFlight(traveller));
        assertEquals(TransitRegistry.Claim.RECLAIMED_STALE, registry.begin(traveller),
            "an abandoned claim must be taken over, and the takeover must be reportable");
    }

    @Test
    void aClaimSurvivesRightUpToTheTimeout() {
        registry.begin(traveller);

        clock.addAndGet(TIMEOUT - 1);

        assertTrue(registry.isInFlight(traveller));
        assertEquals(TransitRegistry.Claim.BUSY, registry.begin(traveller));
    }

    @Test
    void reclaimingRestartsTheClock() {
        registry.begin(traveller);
        clock.addAndGet(TIMEOUT);
        registry.begin(traveller);

        clock.addAndGet(TIMEOUT - 1);
        assertEquals(TransitRegistry.Claim.BUSY, registry.begin(traveller),
            "the replacement journey gets a full window of its own");
    }

    @Test
    void travellersAreTrackedIndependently() {
        UUID other = UUID.randomUUID();

        assertEquals(TransitRegistry.Claim.CLAIMED, registry.begin(traveller));
        assertEquals(TransitRegistry.Claim.CLAIMED, registry.begin(other),
            "one player's journey must not block another's");

        registry.end(traveller);

        assertFalse(registry.isInFlight(traveller));
        assertTrue(registry.isInFlight(other));
    }

    @Test
    void releasingAPlayerWhoHoldsNoClaimIsHarmless() {
        registry.end(traveller);
        registry.end(traveller);

        assertFalse(registry.isInFlight(traveller));
        assertEquals(0, registry.size());
    }

    @Test
    void anUnusableTimeoutIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new TransitRegistry(0));
        assertThrows(IllegalArgumentException.class, () -> new TransitRegistry(-1));
    }
}
