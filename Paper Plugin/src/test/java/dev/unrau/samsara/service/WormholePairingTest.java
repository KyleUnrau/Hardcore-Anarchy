package dev.unrau.samsara.service;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wormhole pairings are never written down, so these identities are the only thing standing between
 * a player and a one-way trip into the void. Every test here is really the same question: does the
 * gateway at the far end come back?
 */
class WormholePairingTest {

    private static final int CELL = 16;
    private static final long SEED = 0L;

    /** The whole End, which is the only size the plugin ever builds this at. */
    private static final int BORDER = DimensionalMapping.VANILLA_LIMIT;

    private WormholePairing pairing() {
        return WormholePairing.covering(CELL, BORDER, SEED);
    }

    // -------------------------------------------------------------------------
    // The two identities everything rests on
    // -------------------------------------------------------------------------

    @Test
    void everyWormholeComesBack() {
        WormholePairing pairing = pairing();
        ThreadLocalRandom random = ThreadLocalRandom.current();

        for (int i = 0; i < 20_000; i++) {
            int x = random.nextInt(-BORDER, BORDER);
            int z = random.nextInt(-BORDER, BORDER);

            Coord there = pairing.partnerOf(x, z);
            Coord back = pairing.partnerOf(there.x(), there.z());

            assertEquals(pairing.cellCentreOf(x, z), back,
                "a wormhole from " + x + "," + z + " via " + there.key() + " did not come back");
        }
    }

    /**
     * The invariant that stops the End filling up with pairs of gateways standing eight blocks
     * apart. A wormhole is only ever built at a cell centre, and this is why: the pairing returns a
     * traveller to a centre, so a gateway anywhere else in its cell can be left from but never
     * arrived at. Go back and forth through one and the arrival builds a second gateway beside the
     * first, then a third, until the cell is a thicket.
     */
    @Test
    void aWormholeBuiltAtACellCentreIsAFixedPointOfTheNetwork() {
        WormholePairing pairing = pairing();
        ThreadLocalRandom random = ThreadLocalRandom.current();

        for (int i = 0; i < 20_000; i++) {
            Coord site = pairing.cellCentreOf(random.nextInt(-BORDER, BORDER),
                random.nextInt(-BORDER, BORDER));

            Coord there = pairing.partnerOf(site.x(), site.z());
            assertEquals(there, pairing.cellCentreOf(there.x(), there.z()),
                "a wormhole must come out at a cell centre, not " + there.key());
            assertEquals(site, pairing.partnerOf(there.x(), there.z()),
                "the round trip from " + site.key() + " via " + there.key() + " missed by more than nothing");
        }
    }

    @Test
    void noWormholeLeadsToItself() {
        WormholePairing pairing = pairing();
        ThreadLocalRandom random = ThreadLocalRandom.current();

        for (int i = 0; i < 20_000; i++) {
            int x = random.nextInt(-BORDER, BORDER);
            int z = random.nextInt(-BORDER, BORDER);

            assertNotEquals(pairing.cellCentreOf(x, z), pairing.partnerOf(x, z),
                "the wormhole at " + x + "," + z + " is a dud that goes nowhere");
        }
    }

    /**
     * The bug this network was rebuilt for. A player stood at End 19997184,-9998848, well past the
     * old reach of 8,388,608, jumped to 4978296,3428456 — and the gateway waiting there sent them to
     * 8388600,-8388600 instead, which bounced them back and forth forever. Every coordinate beyond
     * the reach was folded onto the edge cell, so all of them shared that one destination and none
     * of them could be returned to.
     */
    @Test
    void aGatewayFarPastTheOldReachStillComesBack() {
        WormholePairing pairing = pairing();

        Coord stranded = new Coord(19_997_184, -9_998_848);
        Coord there = pairing.partnerOf(stranded.x(), stranded.z());
        Coord back = pairing.partnerOf(there.x(), there.z());

        assertEquals(pairing.cellCentreOf(stranded.x(), stranded.z()), back,
            "the wormhole at " + stranded.key() + " went to " + there.key() + " and came back to "
                + back.key());
        assertTrue(Math.sqrt(stranded.distanceSquaredTo(back)) < CELL,
            "a round trip must land within a cell of where it started, not " + back.key());
    }

    @Test
    void everyCornerOfTheEndComesBack() {
        WormholePairing pairing = pairing();
        int edge = pairing.reach() - CELL;

        // The whole world border, not a comfortable middle: folding used to begin at 8,388,608 and
        // these are the coordinates that exposed it.
        for (int x : new int[] {-edge, -8_388_608, -1, 8_388_608, edge}) {
            for (int z : new int[] {-edge, -8_388_608, -1, 8_388_608, edge}) {
                Coord there = pairing.partnerOf(x, z);
                assertEquals(pairing.cellCentreOf(x, z), pairing.partnerOf(there.x(), there.z()),
                    "the wormhole at " + x + "," + z + " did not come back from " + there.key());
            }
        }
    }

    @Test
    void theIdentitiesHoldAtTheEdgesOfTheNetwork() {
        WormholePairing pairing = pairing();
        int edge = pairing.reach() - CELL;

        for (int x : new int[] {-edge, -CELL, 0, CELL, edge}) {
            for (int z : new int[] {-edge, -CELL, 0, CELL, edge}) {
                Coord there = pairing.partnerOf(x, z);

                assertTrue(Math.abs(there.x()) <= pairing.reach(), "escaped the network at " + there.key());
                assertTrue(Math.abs(there.z()) <= pairing.reach(), "escaped the network at " + there.key());
                assertEquals(pairing.cellCentreOf(x, z), pairing.partnerOf(there.x(), there.z()));
                assertNotEquals(pairing.cellCentreOf(x, z), there);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Determinism: nothing is stored, so the arithmetic must not wander
    // -------------------------------------------------------------------------

    @Test
    void aFreshInstanceAgreesWithTheOldOne() {
        // Stands in for a server restart, a /samsara reload, and a plugin update: the pairing is rebuilt
        // from config every time it is used, and must land on the same answer.
        for (int x = -3_000_000; x <= 3_000_000; x += 613_337) {
            assertEquals(pairing().partnerOf(x, -x / 3), pairing().partnerOf(x, -x / 3));
        }
    }

    @Test
    void everyGatewayInOneCellSharesOneDestination() {
        WormholePairing pairing = pairing();
        Coord destination = pairing.partnerOf(1_024_000, -512_000);

        for (int dx = 0; dx < CELL; dx++) {
            for (int dz = 0; dz < CELL; dz++) {
                assertEquals(destination, pairing.partnerOf(1_024_000 + dx, -512_000 + dz));
            }
        }
    }

    @Test
    void aDifferentSeedIsADifferentNetwork() {
        WormholePairing other = WormholePairing.covering(CELL, BORDER, 99L);

        int differences = 0;
        for (int x = -2_000_000; x <= 2_000_000; x += 411_119) {
            if (!pairing().partnerOf(x, x / 2).equals(other.partnerOf(x, x / 2))) differences++;
        }

        assertTrue(differences >= 9, "changing the seed should repoint essentially every wormhole");
    }

    // -------------------------------------------------------------------------
    // The point of the exercise: distance and spread
    // -------------------------------------------------------------------------

    @Test
    void wormholesTravelVeryFar() {
        WormholePairing pairing = pairing();
        ThreadLocalRandom random = ThreadLocalRandom.current();

        int distant = 0;
        int samples = 2_000;
        for (int i = 0; i < samples; i++) {
            int x = random.nextInt(-BORDER, BORDER);
            int z = random.nextInt(-BORDER, BORDER);
            Coord there = pairing.partnerOf(x, z);
            if (Math.sqrt(new Coord(x, z).distanceSquaredTo(there)) > 1_000_000) distant++;
        }

        // A uniform destination over a network this size is a very long way off nearly always. The
        // threshold is loose because the point is "millions of blocks", not a precise distribution.
        assertTrue(distant > samples * 0.8,
            "only " + distant + " of " + samples + " jumps travelled over a million blocks");
    }

    @Test
    void neighbouringGatewaysScatterRatherThanTravelTogether() {
        WormholePairing pairing = pairing();

        // Adjacent cells must not land in adjacent cells, or a wormhole would be a short hop with
        // extra steps and the network would fold into a grid you could walk.
        Coord a = pairing.partnerOf(0, 0);
        Coord b = pairing.partnerOf(CELL, 0);
        Coord c = pairing.partnerOf(0, CELL);

        assertTrue(Math.sqrt(a.distanceSquaredTo(b)) > 100_000, a.key() + " and " + b.key() + " are neighbours");
        assertTrue(Math.sqrt(a.distanceSquaredTo(c)) > 100_000, a.key() + " and " + c.key() + " are neighbours");
    }

    @Test
    void thePairingIsAPerfectMatchingOverASmallNetwork() {
        // Small enough to enumerate exhaustively: 8x8 cells. Every cell must appear exactly once as
        // somebody's destination — no cell orphaned, none serving two origins.
        assertIsAPerfectMatching(new WormholePairing(CELL, 4, SEED), 4);
    }

    @Test
    void thePairingIsAPerfectMatchingWhenTheCellCountIsNotAPowerOfTwo() {
        // The case the old power-of-two index space could not represent at all, and the reason
        // players ended up outside the network: 13 is exactly as valid a size as 16 now, so the
        // network can be cut to the world border rather than to the nearest power of two below it.
        assertIsAPerfectMatching(new WormholePairing(CELL, 13, SEED), 13);
    }

    private void assertIsAPerfectMatching(WormholePairing pairing, int halfCells) {
        Map<Coord, Coord> partners = new HashMap<>();
        Set<Coord> destinations = new HashSet<>();

        for (int i = -halfCells; i < halfCells; i++) {
            for (int j = -halfCells; j < halfCells; j++) {
                Coord from = pairing.cellCentreOf(i * CELL, j * CELL);
                Coord to = pairing.partnerOf(from.x(), from.z());
                partners.put(from, to);
                destinations.add(to);
            }
        }

        int cells = 4 * halfCells * halfCells;
        assertEquals(cells, partners.size());
        assertEquals(cells, destinations.size(), "the pairing must be a bijection, with nothing orphaned");
        partners.forEach((from, to) ->
            assertEquals(from, partners.get(to), from.key() + " and " + to.key() + " disagree"));
    }

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    @Test
    void theReachIsCutToWholeCellsAndNothingCoarser() {
        // 3,000,000 / 16 = 187,500 cells exactly. The old implementation rounded that down to
        // 131,072 cells — and the 903,152 blocks it gave up were not merely unserved, they were
        // folded onto the edge, which is what stranded travellers out there.
        WormholePairing pairing = WormholePairing.covering(16, 3_000_000, SEED);

        assertEquals(3_000_000, pairing.reach());

        // A radius that is not a whole number of cells loses less than one cell, never more.
        assertEquals(2_999_984, WormholePairing.covering(16, 2_999_999, SEED).reach());
    }

    @Test
    void nonsensicalSettingsAreRejectedRatherThanSilentlyStrandingPlayers() {
        assertThrows(IllegalArgumentException.class, () -> new WormholePairing(0, 16, SEED));
        assertThrows(IllegalArgumentException.class, () -> new WormholePairing(16, 0, SEED));
        assertThrows(IllegalArgumentException.class, () -> WormholePairing.covering(0, 1_000, SEED));
    }
}
