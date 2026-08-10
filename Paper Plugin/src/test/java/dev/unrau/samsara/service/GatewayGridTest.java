package dev.unrau.samsara.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gateway grid is what lets a player leave the End somewhere other than where they came in,
 * without anyone having defeated the dragon and without a list of gateways being kept anywhere. It
 * is a pure function of position and seed, which is the whole reason a restart cannot lose it.
 */
class GatewayGridTest {

    private static final int SPACING = 512;
    private static final int SEPARATION = 128;
    private static final int EXCLUSION = 1024;
    private static final int LIMIT = DimensionalMapping.VANILLA_LIMIT;
    private static final long SEED = 0x5EEDL;

    private GatewayGrid grid() {
        return grid(SEED);
    }

    private GatewayGrid grid(long seed) {
        return new GatewayGrid(SPACING, SEPARATION, EXCLUSION, LIMIT, seed);
    }

    @Test
    void everyCellHoldsExactlyOneNodeSomewhereInsideIt() {
        GatewayGrid grid = grid();

        for (int i = -20; i <= 20; i++) {
            for (int j = -20; j <= 20; j++) {
                Coord node = grid.node(i, j);
                assertTrue(node.x() >= i * SPACING && node.x() < (i + 1) * SPACING,
                    "node " + node.key() + " escaped cell " + i + "," + j);
                assertTrue(node.z() >= j * SPACING && node.z() < (j + 1) * SPACING,
                    "node " + node.key() + " escaped cell " + i + "," + j);
            }
        }
    }

    /**
     * The margin the separation leaves inside each cell edge does double duty: it keeps neighbours
     * apart, and it keeps the scatter off the axes and away from End 0,0 without a special case.
     */
    @Test
    void noNodeCanLandOnAnAxisOrOnEndZeroZero() {
        GatewayGrid grid = grid();

        for (int i = -30; i <= 30; i++) {
            for (int j = -30; j <= 30; j++) {
                Coord node = grid.node(i, j);
                assertNotEquals(0, node.x(), "node " + node.key() + " sits on the z axis");
                assertNotEquals(0, node.z(), "node " + node.key() + " sits on the x axis");
            }
        }
    }

    @Test
    void neighbouringNodesAreNeverCloserThanTheSeparation() {
        GatewayGrid grid = grid();

        for (int i = -25; i <= 25; i++) {
            for (int j = -25; j <= 25; j++) {
                Coord node = grid.node(i, j);
                for (int di = -1; di <= 1; di++) {
                    for (int dj = -1; dj <= 1; dj++) {
                        if (di == 0 && dj == 0) continue;
                        Coord neighbour = grid.node(i + di, j + dj);
                        double gap = Math.sqrt(node.distanceSquaredTo(neighbour));
                        assertTrue(gap >= SEPARATION,
                            node.key() + " and " + neighbour.key() + " are only " + (long) gap
                                + " blocks apart");
                    }
                }
            }
        }
    }

    /**
     * The reason the scatter exists at all.
     *
     * <p>On a lattice a player flying due east is either in a row of nodes, meeting one every cell
     * forever, or between two rows, meeting none forever — and nothing they do in the air changes
     * which. Scattered, every straight course samples the same spread, so the worst starting line is
     * still a course that finds gateways.
     *
     * <p>The measure is closest approach to the flight line, not distance from any one point along
     * it: a player flying east passes through every x, so what decides whether a gateway is spotted
     * is how far off the line it sits.
     */
    @Test
    void aStraightCardinalFlightCannotMissGatewaysForever() {
        GatewayGrid grid = grid();
        int sightRange = 128;
        int cellsFlown = 40;
        int firstCell = 40;

        // Sweep the whole width of a cell, so the worst possible line is included rather than hoped
        // past: every one of them must pass a gateway within a few thousand blocks.
        for (int z = 0; z < SPACING; z += 16) {
            int flightZ = firstCell * SPACING + z;
            int rowJ = Math.floorDiv(flightZ, SPACING);

            boolean sighted = false;
            for (int i = firstCell; i < firstCell + cellsFlown && !sighted; i++) {
                for (int j = rowJ - 1; j <= rowJ + 1 && !sighted; j++) {
                    Coord node = grid.node(i, j);
                    sighted = grid.isEligible(node) && Math.abs(node.z() - flightZ) <= sightRange;
                }
            }
            assertTrue(sighted, "flying east along z=" + flightZ + " passed no gateway within "
                + sightRange + " blocks in " + (cellsFlown * SPACING) + " blocks of travel");
        }
    }

    /**
     * The bound behind the test above, stated directly.
     *
     * <p>The margin the separation reserves is a band of coordinates no gateway may occupy, so a
     * course down the middle of one is blind until sight range reaches half the separation. That is
     * the residual of the lattice's blind rows, and the only thing keeping it harmless is that the
     * separation is small: half of 128 is 64 blocks, four chunks, which every player can see.
     */
    @Test
    void noCourseIsFurtherThanHalfTheSeparationFromAPossibleGateway() {
        boolean[] reachable = new boolean[SPACING];
        GatewayGrid grid = grid();
        for (int i = 0; i < 400; i++) {
            for (int j = 0; j < 400; j++) {
                reachable[Math.floorMod(grid.node(i, j).z(), SPACING)] = true;
            }
        }

        int worstGap = 0;
        int run = 0;
        // Twice round, so a gap straddling the cell boundary is measured whole.
        for (int pass = 0; pass < 2; pass++) {
            for (int z = 0; z < SPACING; z++) {
                run = reachable[z] ? 0 : run + 1;
                worstGap = Math.max(worstGap, run);
            }
        }

        assertEquals(SEPARATION, worstGap,
            "the band no gateway can occupy must be exactly the separation, no wider");
        assertTrue(worstGap / 2 <= 64, "a straight course must never be blind beyond 64 blocks");
    }

    @Test
    void theDragonIslandIsLeftAlone() {
        GatewayGrid grid = grid();

        assertTrue(grid.nodesWithin(0, 0, EXCLUSION - 1).isEmpty(),
            "no gateway may be built on the central island");
        assertFalse(grid.isEligible(new Coord(300, 300)));
        assertTrue(grid.isEligible(new Coord(2_000, 2_000)));
    }

    @Test
    void aTravellerNearANodeFindsIt() {
        GatewayGrid grid = grid();
        Coord node = grid.node(4, -3);

        List<Coord> found = grid.nodesWithin(node.x() + 100, node.z() - 60, 192);

        assertTrue(found.contains(node), "a node 116 blocks away must be found");
    }

    /**
     * A node can sit anywhere in its cell, so a search must sweep one cell wider than its radius.
     * Getting this wrong hides nodes only near the edge of the radius, which is exactly where a
     * player approaching one first comes into range.
     */
    @Test
    void aNodeAtTheFarCornerOfItsCellIsStillFound() {
        GatewayGrid grid = grid();

        for (int i = -12; i <= 12; i++) {
            for (int j = -12; j <= 12; j++) {
                Coord node = grid.node(i, j);
                if (!grid.isEligible(node)) continue;

                // Search from a cell away, with a radius that only just reaches the node.
                int fromX = node.x() - SPACING;
                int distance = (int) Math.ceil(Math.sqrt(node.distanceSquaredTo(new Coord(fromX, node.z()))));
                assertTrue(grid.nodesWithin(fromX, node.z(), distance).contains(node),
                    "node " + node.key() + " was missed at the edge of the search radius");
            }
        }
    }

    @Test
    void nothingIsFoundWhereThereIsNoNodeNearby() {
        GatewayGrid grid = grid();
        Coord node = grid.node(6, 6);

        // Far enough from that node to be out of range, and inside its own cell's reach of nothing.
        assertTrue(grid.nodesWithin(node.x(), node.z(), 8).size() <= 1);
        assertTrue(grid.nodesWithin(node.x() + 4, node.z() + 4, 1).isEmpty());
    }

    @Test
    void nearbyNodesComeBackNearestFirst() {
        GatewayGrid grid = grid();
        Coord from = grid.node(12, 12);

        List<Coord> found = grid.nodesWithin(from.x(), from.z(), SPACING * 2);

        assertFalse(found.isEmpty());
        assertEquals(from, found.getFirst());
        for (int i = 1; i < found.size(); i++) {
            long previous = found.get(i - 1).distanceSquaredTo(from);
            long current = found.get(i).distanceSquaredTo(from);
            assertTrue(current >= previous, "results must be ordered by distance");
        }
    }

    @Test
    void theLayoutIsTheSameEverySessionSoARestartChangesNothing() {
        GatewayGrid before = grid();
        GatewayGrid after = grid();

        assertEquals(before.nodesWithin(412_345, -98_765, SPACING),
            after.nodesWithin(412_345, -98_765, SPACING));
        assertEquals(before.node(-9_001, 7_777), after.node(-9_001, 7_777));
    }

    @Test
    void aDifferentSeedLaysTheEndOutDifferently() {
        GatewayGrid one = grid(1L);
        GatewayGrid other = grid(2L);

        int same = 0;
        for (int i = 0; i < 40; i++) {
            if (one.node(i, i).equals(other.node(i, i))) same++;
        }
        assertTrue(same <= 1, "two seeds produced near-identical layouts");
    }

    /** The scatter must use the whole window it is given, not cluster in a corner of each cell. */
    @Test
    void offsetsCoverTheWholeWindowRatherThanFavouringOneSpot() {
        GatewayGrid grid = grid();
        Set<Integer> offsets = new HashSet<>();

        for (int i = 0; i < 60; i++) {
            for (int j = 0; j < 60; j++) {
                offsets.add(Math.floorMod(grid.node(i, j).x(), SPACING));
            }
        }

        assertTrue(offsets.size() > 200, "only " + offsets.size() + " distinct offsets in 3600 cells");
        assertTrue(offsets.stream().allMatch(o -> o >= SEPARATION / 2 && o < SPACING - SEPARATION / 2),
            "an offset escaped the window the separation allows");
    }

    @Test
    void neighbouringNodesLeadToUnrelatedPartsOfTheEnd() {
        GatewayGrid grid = grid();
        WormholePairing pairing = WormholePairing.covering(16, DimensionalMapping.VANILLA_LIMIT, 0L);

        // Walking to the next node along is worth doing: two nodes one cell apart come out in
        // completely different regions, so the grid is a set of choices rather than a conveyor.
        Coord here = grid.node(10, 10);
        Coord next = grid.node(11, 10);
        Coord first = pairing.partnerOf(here.x(), here.z());
        Coord second = pairing.partnerOf(next.x(), next.z());

        assertTrue(Math.sqrt(first.distanceSquaredTo(second)) > 100_000,
            first.key() + " and " + second.key() + " are too close to be worth choosing between");
    }

    /**
     * The duplicate-gateway bug, and the reason {@code EndGatewayNetwork} snaps a node to its
     * pairing cell before building anything.
     *
     * <p>A scattered node lands on the centre of the 16-block cell containing it only by accident.
     * Jumping away from a gateway built off-centre and coming back arrived at the <em>centre</em> —
     * near enough to see the first gateway, far enough to build a second one beside it. A few round
     * trips and the node was a thicket.
     */
    @Test
    void aNodeSnappedToItsCellIsWhereARoundTripComesBack() {
        GatewayGrid grid = grid();
        WormholePairing pairing = WormholePairing.covering(16, DimensionalMapping.VANILLA_LIMIT, 0L);

        Coord raw = grid.node(3, 5);
        Coord site = pairing.cellCentreOf(raw.x(), raw.z());

        assertTrue(Math.sqrt(raw.distanceSquaredTo(site)) < 16,
            "snapping must not move a node out of its own cell");

        Coord there = pairing.partnerOf(site.x(), site.z());
        assertEquals(site, pairing.partnerOf(there.x(), there.z()),
            "a snapped node must be exactly where the return trip lands");
    }

    @Test
    void everySnappedNodeAcrossTheEndComesBackToItself() {
        GatewayGrid grid = grid();
        WormholePairing pairing = WormholePairing.covering(16, DimensionalMapping.VANILLA_LIMIT, 0L);

        for (int i = -60; i <= 60; i += 7) {
            for (int j = -60; j <= 60; j += 11) {
                Coord node = grid.node(i, j);
                if (!grid.isEligible(node)) continue;

                Coord site = pairing.cellCentreOf(node.x(), node.z());
                Coord there = pairing.partnerOf(site.x(), site.z());

                assertEquals(site, pairing.partnerOf(there.x(), there.z()),
                    "the node at " + node.key() + " does not get its own gateway back");
            }
        }
    }

    @Test
    void nodesBeyondTheWorldBorderAreRefused() {
        GatewayGrid narrow = new GatewayGrid(SPACING, SEPARATION, EXCLUSION, 10_000, SEED);

        assertTrue(narrow.isEligible(new Coord(5_000, 5_000)));
        assertFalse(narrow.isEligible(new Coord(20_000, 0)));
        assertTrue(narrow.nodesWithin(29_000_000, 0, SPACING).isEmpty());
    }

    @Test
    void thereIsAlwaysANearestNodeInOrdinaryTerritory() {
        assertNotNull(grid().nearest(250_000, -80_000));
    }

    @Test
    void nonsensicalSettingsAreRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new GatewayGrid(8, SEPARATION, EXCLUSION, LIMIT, SEED));
        assertThrows(IllegalArgumentException.class,
            () -> new GatewayGrid(SPACING, 0, EXCLUSION, LIMIT, SEED));
        assertThrows(IllegalArgumentException.class,
            () -> new GatewayGrid(SPACING, SPACING, EXCLUSION, LIMIT, SEED),
            "a separation of a whole cell leaves the scatter nowhere to go");
        assertThrows(IllegalArgumentException.class,
            () -> new GatewayGrid(SPACING, SEPARATION, -1, LIMIT, SEED));
        assertThrows(IllegalArgumentException.class,
            () -> new GatewayGrid(SPACING, SEPARATION, EXCLUSION, 100, SEED));
    }
}
