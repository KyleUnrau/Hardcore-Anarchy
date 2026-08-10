package dev.unrau.samsara.social;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What "spending time together" is allowed to mean.
 *
 * <p>The rule the automatic contact rests on is that nearness <em>accumulates</em> and also
 * <em>fades</em>. Without the first, only an unbroken stretch would ever count and nobody would
 * reach the threshold across an evening of coming and going. Without the second, walking past the
 * same player at spawn once a week would eventually add up to a permanent relationship — which is
 * exactly the "briefly pass near one another" case the threshold exists to exclude.
 */
class SocialDataTest {

    private static final long MINUTE = 60_000L;

    /** A quarter of a second lost per second apart, dropped outright after three hours. */
    private static final SocialData.Fade FADE = new SocialData.Fade(0.25, 180 * MINUTE);

    /** Nothing fades, so a test can talk about accumulation without the decay in the way. */
    private static final SocialData.Fade NEVER = new SocialData.Fade(0, Long.MAX_VALUE);

    private final SocialData data = new SocialData();
    private final UUID other = UUID.randomUUID();

    @Test
    void timeTogetherAddsUpAcrossSeparateVisits() {
        long start = 1_000_000L;
        data.recordProximity(other, 300, 0, start, NEVER);
        // Away for an hour, then back. The same evening, the same two people.
        double total = data.recordProximity(other, 300, 60 * 60, start + 60 * MINUTE, NEVER);

        assertEquals(600, total, 0.001);
        assertEquals(600, data.proximitySeconds(other, start + 60 * MINUTE, NEVER), 0.001);
    }

    @Test
    void timeApartIsChargedAgainstTheScoreOnTheWayBack() {
        long start = 1_000_000L;
        data.recordProximity(other, 600, 0, start, FADE);

        // Twenty minutes away, at a quarter of a second per second: five minutes off the ten banked.
        double total = data.recordProximity(other, 0, 20 * 60, start + 20 * MINUTE, FADE);

        assertEquals(300, total, 0.001, "a pair who stopped seeing each other lose ground");
    }

    @Test
    void theSampleItselfIsNotChargedAsTimeApart() {
        long start = 1_000_000L;
        data.recordProximity(other, 5, 0, start, FADE);
        // Five seconds later, still together: the caller reports no time apart, so the whole
        // interval is credited and none of it is taken back.
        double total = data.recordProximity(other, 5, 0, start + 5_000L, FADE);

        assertEquals(10, total, 0.001);
    }

    @Test
    void aScoreFadesToNothingRatherThanGoingNegative() {
        long start = 1_000_000L;
        data.recordProximity(other, 60, 0, start, FADE);

        assertEquals(0, data.proximitySeconds(other, start + 179 * MINUTE, FADE), 0.001);
    }

    @Test
    void readingAScoreTakesTheTimeApartOffWithoutBankingIt() {
        long start = 1_000_000L;
        data.recordProximity(other, 600, 0, start, FADE);

        assertEquals(300, data.proximitySeconds(other, start + 20 * MINUTE, FADE), 0.001);
        assertEquals(600, data.proximitySeconds(other, start, FADE), 0.001,
            "the fade is worked out from when they were last together, not written back over it");
    }

    @Test
    void aGapLongerThanTheWindowStartsAgainFromNothing() {
        long start = 1_000_000L;
        // Decay off, so the only thing that can drop this is the backstop.
        SocialData.Fade backstopOnly = new SocialData.Fade(0, 180 * MINUTE);
        data.recordProximity(other, 900, 0, start, backstopOnly);

        assertEquals(0, data.proximitySeconds(other, start + 180 * MINUTE + 1, backstopOnly), 0.001,
            "twenty minutes from last year is not twenty minutes towards a relationship today");
    }

    @Test
    void progressIsPerPair() {
        UUID third = UUID.randomUUID();
        data.recordProximity(other, 120, 0, 1_000_000L, NEVER);
        data.recordProximity(third, 30, 0, 1_000_000L, NEVER);

        assertEquals(120, data.proximitySeconds(other, 1_000_000L, NEVER), 0.001);
        assertEquals(30, data.proximitySeconds(third, 1_000_000L, NEVER), 0.001);
    }

    @Test
    void pruningDropsOnlyWhatHasFadedAway() {
        long start = 1_000_000L;
        UUID stale = UUID.randomUUID();
        data.recordProximity(stale, 60, 0, start, FADE);
        data.recordProximity(other, 600, 0, start + 5 * MINUTE, FADE);

        // Four minutes past the last sample: one minute of score is gone, ten minutes' is not.
        assertEquals(1, data.pruneProximity(start + 9 * MINUTE, FADE));

        assertEquals(0, data.proximitySeconds(stale, start + 9 * MINUTE, FADE), 0.001);
        assertTrue(data.proximitySeconds(other, start + 9 * MINUTE, FADE) > 0);
    }

    @Test
    void becomingContactsForgetsTheProgressTowardsIt() {
        data.recordProximity(other, 900, 0, 1_000_000L, NEVER);
        data.addContact(other, "Bob");

        assertEquals(0, data.proximitySeconds(other, 1_000_000L, NEVER),
            "the relationship exists now; the running total towards it is nobody's business");
    }

    @Test
    void severingAPairingForgetsTheProgressToo() {
        data.recordProximity(other, 900, 0, 1_000_000L, NEVER);
        data.suppressAuto(other);

        assertEquals(0, data.proximitySeconds(other, 1_000_000L, NEVER), 0.001,
            "progress banked before a removal must not be waiting to fire the moment the"
                + " suppression is lifted");
        assertTrue(data.isAutoSuppressed(other));
    }

    @Test
    void addingAContactClearsTheSuppressionBetweenThem() {
        data.suppressAuto(other);
        data.addContact(other, "Bob");

        assertFalse(data.isAutoSuppressed(other));
    }

    @Test
    void theServerDefaultAppliesUntilThePlayerHasAnOpinion() {
        assertFalse(data.autoContactsEnabled(false));
        assertTrue(data.autoContactsEnabled(true));

        data.setAutoContacts(false);
        assertFalse(data.autoContactsEnabled(true),
            "a player who said no keeps saying no when the server changes its default");
    }

    @Test
    void aRecordIsCleanUntilSomethingChanges() {
        data.clearDirty();
        assertFalse(data.isDirty());

        data.refreshContactName(other, "Bob");
        assertFalse(data.isDirty(), "renaming somebody who is not a contact changes nothing");

        data.addContact(other, "Bob");
        assertTrue(data.isDirty());
    }
}
