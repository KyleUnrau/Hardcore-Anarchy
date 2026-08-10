package dev.unrau.samsara.social;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a distance is worth.
 *
 * <p>This one number decides both halves of the score: it is how fast a pair earn, and one minus it
 * is how fast they lose. So the property that matters is that it is continuous — no distance at
 * which a pair suddenly stop building or suddenly start losing — because a cliff there would be a
 * player standing still while the server decided their relationship on a rounding error.
 */
class ProximityScannerTest {

    private static final double CLOSE = 16;
    private static final double FAR = 48;

    private static double closeness(double distance) {
        return ProximityScanner.closeness(distance, CLOSE, FAR);
    }

    @Test
    void thereIsNoDifferenceBetweenNearAndNearer() {
        assertEquals(1, closeness(0), 0.001);
        assertEquals(1, closeness(CLOSE), 0.001, "the close radius is a room, not a gradient");
    }

    @Test
    void creditRunsOutExactlyAtTheRadius() {
        assertEquals(0, closeness(FAR), 0.001);
        assertEquals(0, closeness(FAR + 1), 0.001);
        assertEquals(0, closeness(500_000), 0.001);
    }

    @Test
    void theTaperBetweenThemIsGradual() {
        assertEquals(0.75, closeness(24), 0.001);
        assertEquals(0.5, closeness(32), 0.001);
        assertEquals(0.25, closeness(40), 0.001);
    }

    @Test
    void nothingJumpsAtEitherBoundary() {
        // The boundaries are where a cliff would hide, and a cliff is what turns "drifting apart"
        // into "crossed a line".
        assertTrue(Math.abs(closeness(CLOSE) - closeness(CLOSE + 0.01)) < 0.01);
        assertTrue(Math.abs(closeness(FAR) - closeness(FAR - 0.01)) < 0.01);
    }

    @Test
    void theTimeAtTheEdgeIsAlmostEntirelyTimeApart() {
        // What the scanner charges as absence. A pair at the far edge are billed for nearly the
        // whole interval, which is why they stop drifting upwards rather than parking on a score.
        assertTrue(1 - closeness(FAR - 1) > 0.9);
    }

    @Test
    void crossedRadiiDegradeToAllOrNothing() {
        // The config layer squares these up and warns; if one ever reaches here anyway, the answer
        // is a room with a hard edge rather than a division by zero.
        assertEquals(1, ProximityScanner.closeness(30, 48, 48), 0.001);
        assertEquals(0, ProximityScanner.closeness(49, 48, 48), 0.001);
        assertEquals(1, ProximityScanner.closeness(30, 48, 16), 0.001);
        assertEquals(0, ProximityScanner.closeness(60, 48, 16), 0.001);
    }
}
