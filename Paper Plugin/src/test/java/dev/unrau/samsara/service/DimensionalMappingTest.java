package dev.unrau.samsara.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reflection is the one part of this feature that must never drift. A traveller's way home is
 * not written down anywhere — it is recomputed from where they are standing — so if this transform
 * changes, every home gateway in the world silently changes where it leads.
 */
class DimensionalMappingTest {

    private static final int SPACING = 16;

    private DimensionalMapping mapping() {
        return DimensionalMapping.vanilla(SPACING);
    }

    // -------------------------------------------------------------------------
    // Overworld <-> End
    // -------------------------------------------------------------------------

    @Test
    void theEndReflectsBothTheSignAndTheAxes() {
        // (x, z) -> (-z, -x). The axis swap is the half that is easy to lose in a refactor, because
        // a sign-only inversion passes every round-trip test on its own.
        assertEquals(new Coord(-2_000, -1_000), DimensionalMapping.vanilla(1).overworldToEnd(1_000, 2_000));
        assertEquals(new Coord(-2_000, -1_000), DimensionalMapping.vanilla(1).endToOverworld(1_000, 2_000));
    }

    @Test
    void aPointOffTheDiagonalDoesNotSurviveASignOnlyInversion() {
        // Guards the axis swap specifically: for any x != z the reflected point differs from the
        // point a plain (-x, -z) would have produced.
        Coord reflected = DimensionalMapping.vanilla(1).overworldToEnd(1_204_776, -853_112);

        assertEquals(new Coord(853_112, -1_204_776), reflected);
        assertNotEquals(new Coord(-1_204_776, 853_112), reflected);
    }

    @Test
    void aDistantStrongholdReachesTheReflectedEndCoordinate() {
        Coord site = mapping().overworldToEnd(1_204_776, -853_112);

        assertTrue(Math.abs(site.x() - 853_112) <= SPACING, "x was " + site.x());
        assertTrue(Math.abs(site.z() - -1_204_776) <= SPACING, "z was " + site.z());
    }

    @Test
    void travellersAreNeverFunnelledTowardsEndZeroZero() {
        // Whatever the origin, the destination is as far from the centre as the origin was.
        for (int x = 100_000; x <= 3_000_000; x += 371_000) {
            Coord site = mapping().overworldToEnd(x, -x / 2);
            assertTrue(site.distanceFromOrigin() > 50_000,
                "Overworld " + x + " should not land near End 0,0, but reached " + site.key());
        }
    }

    @Test
    void theReflectionIsItsOwnInverse() {
        DimensionalMapping mapping = mapping();

        // A portal maps to a site; the home gateway at that site maps back to the portal. This
        // identity is the reason nothing about a journey is ever persisted.
        Coord site = mapping.overworldToEnd(412_345, 98_765);
        Coord home = mapping.endToOverworld(site.x(), site.z());

        assertTrue(Math.abs(home.x() - 412_345) <= SPACING, "returned to x " + home.x());
        assertTrue(Math.abs(home.z() - 98_765) <= SPACING, "returned to z " + home.z());
    }

    @Test
    void theReflectionIsExactWithoutSnapping() {
        DimensionalMapping mapping = DimensionalMapping.vanilla(1);

        Coord site = mapping.overworldToEnd(412_345, 98_765);

        assertEquals(new Coord(412_345, 98_765), mapping.endToOverworld(site.x(), site.z()));
    }

    @Test
    void theSameOriginAlwaysProducesTheSameDestination() {
        assertEquals(mapping().overworldToEnd(412_345, 98_765), mapping().overworldToEnd(412_345, 98_765));
        assertEquals(mapping().endToOverworld(-8_000, 400), mapping().endToOverworld(-8_000, 400));
    }

    @Test
    void snappingAloneDoesNotKeepOnePortalToOneSite() {
        DimensionalMapping mapping = mapping();

        // This test used to assert the opposite, on a portal that happens to sit inside one cell.
        // It does not generalise, and believing it did put a second arrival platform in the chunk
        // next door for very nearly a quarter of the strongholds on the server. A 3x3 opening
        // straddling a cell boundary hands its blocks two different sites.
        Coord centre = mapping.overworldToEnd(400_000, 0);
        Coord edge = mapping.overworldToEnd(400_001, 0);

        assertNotEquals(centre, edge, "two blocks of one portal, two sites — this is the bug");
        assertEquals(16, Math.abs(centre.z() - edge.z()), "and the sites are one chunk apart");
    }

    @Test
    void aQuarterOfAllPortalsStraddleACellBoundary() {
        // The scale of what snapping was being trusted with, and why EndPortalAnchor is not
        // belt-and-braces. Every position a portal centre can take relative to the grid, counted.
        int straddling = 0;

        for (int x = 0; x < SPACING; x++) {
            for (int z = 0; z < SPACING; z++) {
                Set<Coord> sites = new HashSet<>();
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        sites.add(mapping().overworldToEnd(x + dx, z + dz));
                    }
                }
                if (sites.size() > 1) straddling++;
            }
        }

        assertEquals(60, straddling, "60 of 256 portal positions are split by the grid");
    }

    @Test
    void everyPortalBlockInOneRoomSharesOneArrivalSiteOnceAnchored() {
        DimensionalMapping mapping = mapping();

        // The invariant the platform build actually depends on, stated where it is now true: the
        // anchor names the portal's centre first, and the reflection is asked exactly once about it
        // however the traveller was standing. Checked against the worst case above.
        Coord anchored = mapping.overworldToEnd(400_000, 0);

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Coord centre = EndPortalAnchor.centreOf(new Coord(400_000 + dx, dz),
                    PortalSurvey.completePortalAt(new Coord(400_000, 0)));

                assertEquals(anchored, mapping.overworldToEnd(centre.x(), centre.z()),
                    "entering at " + dx + "," + dz + " must reach the one site");
            }
        }
    }

    @Test
    void snappingIsIdempotentSoSitesNeverDrift() {
        DimensionalMapping mapping = mapping();

        Coord site = mapping.overworldToEnd(123_456, -654_321);
        Coord home = mapping.endToOverworld(site.x(), site.z());
        Coord again = mapping.overworldToEnd(home.x(), home.z());

        assertEquals(site, again, "snapping must be idempotent or sites would drift on every visit");
    }

    @Test
    void theReturnColumnStaysWithinHalfACellOfThePortal() {
        // The return does not land on the mapped column — it searches that column's neighbourhood
        // for the portal block itself, because an End portal is underground and the surface above
        // it is the wrong place by hundreds of blocks. That search is bounded by half a cell, so if
        // the round trip ever drifts further than this, returns silently stop finding the portal
        // and degrade to the surface fallback instead.
        int bound = SPACING / 2;

        for (int x = -40; x <= 40; x++) {
            for (int z = -40; z <= 40; z++) {
                Coord site = mapping().overworldToEnd(x, z);
                Coord home = mapping().endToOverworld(site.x(), site.z());

                assertTrue(Math.abs(home.x() - x) <= bound,
                    "x drifted " + Math.abs(home.x() - x) + " from " + x + "," + z);
                assertTrue(Math.abs(home.z() - z) <= bound,
                    "z drifted " + Math.abs(home.z() - z) + " from " + x + "," + z);
            }
        }
    }

    @Test
    void theReturnColumnStaysWithinHalfACellFarFromTheOrigin() {
        // Snapping uses floorDiv, so negative coordinates are the case that breaks first.
        int bound = SPACING / 2;
        Coord portal = new Coord(-1_204_776, 853_112);

        Coord site = mapping().overworldToEnd(portal.x(), portal.z());
        Coord home = mapping().endToOverworld(site.x(), site.z());

        assertTrue(Math.abs(home.x() - portal.x()) <= bound);
        assertTrue(Math.abs(home.z() - portal.z()) <= bound);
    }

    @Test
    void widelySeparatedCivilisationsDoNotShareASite() {
        DimensionalMapping mapping = mapping();

        Coord west = mapping.overworldToEnd(-2_500_000, 640_000);
        Coord east = mapping.overworldToEnd(3_100_000, -1_750_000);
        Coord north = mapping.overworldToEnd(75_000, 4_400_000);

        assertNotEquals(west, east);
        assertNotEquals(west, north);
        assertNotEquals(east, north);
    }

    @Test
    void aHomeGatewayElsewhereInTheEndLeadsElsewhereInTheOverworld() {
        DimensionalMapping mapping = mapping();

        // Entered the End from one stronghold, crossed the End by wormhole, left by another
        // player's platform entirely.
        Coord entered = mapping.overworldToEnd(1_000_000, 1_000_000);
        Coord leftFrom = new Coord(entered.x() + 40_000, entered.z() - 25_000);
        Coord arrived = mapping.endToOverworld(leftFrom.x(), leftFrom.z());

        assertEquals(-leftFrom.z(), arrived.x());
        assertEquals(-leftFrom.x(), arrived.z());
        assertTrue(Math.hypot(arrived.x() - 1_000_000.0, arrived.z() - 1_000_000.0) > 25_000,
            "leaving by a distant platform must land in a distant Overworld region");
    }

    // -------------------------------------------------------------------------
    // Projection into Overworld terms, used by the exile search after a death
    // -------------------------------------------------------------------------

    @Test
    void aDeathIsMeasuredInOverworldTerms() {
        DimensionalMapping mapping = mapping();

        assertEquals(new Coord(500, -900),
            mapping.toOverworld(DimensionalMapping.Realm.OVERWORLD, 500, -900));
        assertEquals(new Coord(900, -500),
            mapping.toOverworld(DimensionalMapping.Realm.END, 500, -900));
        assertEquals(new Coord(4000, -7200),
            mapping.toOverworld(DimensionalMapping.Realm.NETHER, 500, -900));
    }

    @Test
    void netherToOverworldKeepsVanillaEightfoldScaling() {
        // The plugin never routes a Nether portal, but the exile search still has to read a Nether
        // death position on the Overworld's scale.
        assertEquals(new Coord(-384_000, 100_000), mapping().netherToOverworld(-48_000, 12_500));
    }

    // -------------------------------------------------------------------------
    // Extremes and misconfiguration
    // -------------------------------------------------------------------------

    @Test
    void extremeCoordinatesStayInsideTheWorldBorder() {
        DimensionalMapping narrow = new DimensionalMapping(10_000, 10_000, SPACING);

        Coord site = narrow.overworldToEnd(29_999_984, -29_999_984);
        assertTrue(Math.abs(site.x()) <= 10_000, "clamped x was " + site.x());
        assertTrue(Math.abs(site.z()) <= 10_000, "clamped z was " + site.z());

        Coord home = narrow.endToOverworld(-29_999_984, 29_999_984);
        assertTrue(Math.abs(home.x()) <= 10_000, "clamped x was " + home.x());
    }

    @Test
    void theWorldEdgeDoesNotOverflowIntoTheOppositeCorner() {
        DimensionalMapping mapping = mapping();

        // 8 * 29,999,984 overflows a signed int if the scaling is done carelessly, which would read
        // a death at the world edge as one at the far side of the map.
        Coord home = mapping.netherToOverworld(29_999_984, 29_999_984);

        assertEquals(DimensionalMapping.VANILLA_LIMIT, home.x());
        assertEquals(DimensionalMapping.VANILLA_LIMIT, home.z());
    }

    @Test
    void nonsensicalSettingsAreRejectedRatherThanSilentlyMisplacingPlayers() {
        assertThrows(IllegalArgumentException.class, () -> new DimensionalMapping(0, 100, 16));
        assertThrows(IllegalArgumentException.class, () -> new DimensionalMapping(100, -1, 16));
        assertThrows(IllegalArgumentException.class, () -> new DimensionalMapping(100, 100, 0));
    }
}
