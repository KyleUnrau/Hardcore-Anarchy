package dev.unrau.samsara.service;

import dev.unrau.samsara.config.SpawnAreaShape;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spawn selection is the plugin's whole premise, so the band it draws from has to hold up against
 * the configurations a server owner will actually write — including the broken ones. A bad number
 * in config.yml must narrow the area, never stop a spawn from being chosen.
 */
class SpawnAreaTest {

    private static final Logger LOG = Logger.getLogger(SpawnAreaTest.class.getName());
    private static final double NO_BORDER = 60_000_000;   // vanilla default border size

    private SpawnArea area(SpawnAreaShape shape, int min, int max) {
        return SpawnArea.resolve(shape, min, max, 0, 0, NO_BORDER, true, LOG);
    }

    @Test
    void circleNeverReachesTheCornersOfTheMap() {
        SpawnArea area = area(SpawnAreaShape.CIRCLE, 0, 30_000_000);
        Random random = new Random(1);

        for (int i = 0; i < 20_000; i++) {
            int[] point = area.randomPoint(random);
            assertTrue(Math.hypot(point[0], point[1]) <= area.getMax() + 1,
                "circle spawns must stay inside the configured radius");
        }
    }

    @Test
    void squareReachesTheCornersACircleCannot() {
        SpawnArea area = area(SpawnAreaShape.SQUARE, 0, 20_000_000);
        Random random = new Random(2);

        boolean sawACorner = false;
        for (int i = 0; i < 20_000; i++) {
            int[] point = area.randomPoint(random);
            int chebyshev = Math.max(Math.abs(point[0]), Math.abs(point[1]));
            assertTrue(chebyshev <= area.getMax() + 1, "square spawns stay inside the per-axis maximum");
            // A point outside the inscribed circle is one a circle-shaped area could never produce.
            if (Math.hypot(point[0], point[1]) > area.getMax()) sawACorner = true;
        }

        assertTrue(sawACorner, "square must place people beyond the radius a circle would allow");
    }

    @Test
    void bothShapesRespectTheInnerEdge() {
        for (SpawnAreaShape shape : SpawnAreaShape.values()) {
            SpawnArea area = area(shape, 250_000, 5_000_000);
            Random random = new Random(3);

            for (int i = 0; i < 20_000; i++) {
                int[] point = area.randomPoint(random);
                double distance = shape == SpawnAreaShape.SQUARE
                    ? Math.max(Math.abs(point[0]), Math.abs(point[1]))
                    : Math.hypot(point[0], point[1]);
                assertTrue(distance >= area.getMin() - 1,
                    shape + " placed a spawn inside the minimum distance: " + distance);
            }
        }
    }

    @Test
    void spawnsAreSpreadByAreaRatherThanClusteringAtTheInnerEdge() {
        SpawnArea area = area(SpawnAreaShape.CIRCLE, 0, 1_000_000);
        Random random = new Random(4);

        // Half the area of a circle lies beyond radius/sqrt(2); roughly half the samples should too.
        double halfway = 1_000_000 / Math.sqrt(2);
        int beyond = 0;
        int samples = 40_000;
        for (int i = 0; i < samples; i++) {
            int[] point = area.randomPoint(random);
            if (Math.hypot(point[0], point[1]) > halfway) beyond++;
        }

        double fraction = (double) beyond / samples;
        assertTrue(fraction > 0.47 && fraction < 0.53,
            "expected an even spread by area, but " + fraction + " of spawns were in the outer half");
    }

    @Test
    void aBorderSmallerThanTheConfiguredMaximumNarrowsTheArea() {
        SpawnArea area = SpawnArea.resolve(SpawnAreaShape.SQUARE, 0, 30_000_000, 0, 0, 20_000, true, LOG);
        Random random = new Random(5);

        assertTrue(area.getMax() <= 10_000, "the band must fit inside a 20,000 block border");
        for (int i = 0; i < 5_000; i++) {
            int[] point = area.randomPoint(random);
            assertTrue(Math.abs(point[0]) <= 10_000 && Math.abs(point[1]) <= 10_000,
                "no spawn may land outside the border");
        }
    }

    @Test
    void anOffsetBorderThatExcludesTheOriginMovesTheCentre() {
        // A 10,000 block border centred a million blocks out: 0,0 is nowhere near it.
        SpawnArea area = SpawnArea.resolve(SpawnAreaShape.CIRCLE, 0, 5_000_000,
            1_000_000, -500_000, 10_000, true, LOG);
        Random random = new Random(6);

        assertEquals(1_000_000, area.getCenterX());
        assertEquals(-500_000, area.getCenterZ());
        for (int i = 0; i < 5_000; i++) {
            int[] point = area.randomPoint(random);
            assertTrue(Math.abs(point[0] - 1_000_000) <= 5_000, "x must stay inside the offset border");
            assertTrue(Math.abs(point[1] + 500_000) <= 5_000, "z must stay inside the offset border");
        }
    }

    @Test
    void anImpossibleMinimumIsDroppedRatherThanBlockingTheSearch() {
        // Minimum beyond the maximum: an exile still has to happen somewhere.
        SpawnArea area = area(SpawnAreaShape.CIRCLE, 9_000_000, 100_000);

        assertEquals(0, area.getMin());
        assertEquals(100_000, area.getMax());
        assertTrue(area.randomPoint(new Random(7)).length == 2);
    }

    @Test
    void nonsenseDistancesStillProduceAUsableArea() {
        SpawnArea negative = area(SpawnAreaShape.CIRCLE, -500, -1);
        assertTrue(negative.getMax() >= 1, "a usable maximum is substituted for a negative one");

        SpawnArea zero = area(SpawnAreaShape.SQUARE, 0, 0);
        int[] point = zero.randomPoint(new Random(8));
        assertTrue(Math.abs(point[0]) <= zero.getMax() && Math.abs(point[1]) <= zero.getMax());

        SpawnArea beyondTheWorld = area(SpawnAreaShape.SQUARE, 0, Integer.MAX_VALUE);
        assertTrue(beyondTheWorld.getMax() <= SpawnArea.WORLD_LIMIT,
            "no spawn may be chosen past the world's own coordinate limit");
    }

    @Test
    void aNullShapeFallsBackToTheDefaultRatherThanFailing() {
        SpawnArea area = SpawnArea.resolve(null, 0, 100_000, 0, 0, NO_BORDER, true, LOG);
        assertEquals(SpawnAreaShape.SQUARE, area.getShape());
    }
}
