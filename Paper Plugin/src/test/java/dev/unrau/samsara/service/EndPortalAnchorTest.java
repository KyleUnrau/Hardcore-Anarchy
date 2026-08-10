package dev.unrau.samsara.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The anchor exists to make one statement true: <b>a portal has one arrival site, whatever block of
 * it a traveller stood on.</b> It was not true, and the End grew a second platform in the chunk next
 * door for very nearly a quarter of the strongholds on the server.
 *
 * <p>So these tests are mostly one test asked in different worlds — a portal in good repair, a
 * portal with no frames, a portal somebody has taken blocks out of, a portal with a neighbour — and
 * the answer wanted is always the same answer nine times over.
 */
class EndPortalAnchorTest {

    /** The worst case from {@link DimensionalMappingTest}: an opening split on both axes at once. */
    private static final Coord SPLIT_BY_THE_GRID = new Coord(400_000, 0);

    // -------------------------------------------------------------------------
    // One portal, one answer
    // -------------------------------------------------------------------------

    @Test
    void everyBlockOfAPortalNamesItsCentre() {
        Coord centre = new Coord(-1_204_776, 853_112);
        PortalSurvey world = PortalSurvey.completePortalAt(centre);

        for (Coord offset : EndPortalShape.portalOffsets()) {
            Coord standing = new Coord(centre.x() + offset.x(), centre.z() + offset.z());
            assertEquals(centre, EndPortalAnchor.centreOf(standing, world),
                "standing at " + offset.key() + " named the wrong portal");
        }
    }

    @Test
    void everyPortalOnEveryGridOffsetReachesExactlyOneSite() {
        // The regression this was all written for, swept across every position a portal centre can
        // take relative to the arrival grid. Unanchored, 60 of these 256 split into two sites or
        // four; anchored, every one of them is a single platform.
        DimensionalMapping mapping = DimensionalMapping.vanilla(16);

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                Coord centre = new Coord(SPLIT_BY_THE_GRID.x() + x, SPLIT_BY_THE_GRID.z() + z);
                PortalSurvey world = PortalSurvey.completePortalAt(centre);
                Set<Coord> sites = new HashSet<>();

                for (Coord offset : EndPortalShape.portalOffsets()) {
                    Coord anchor = EndPortalAnchor.centreOf(
                        new Coord(centre.x() + offset.x(), centre.z() + offset.z()), world);
                    sites.add(mapping.overworldToEnd(anchor.x(), anchor.z()));
                }

                assertEquals(1, sites.size(),
                    "portal at " + centre.key() + " reached " + sites.size() + " sites: " + sites);
            }
        }
    }

    @Test
    void theSameQuestionAlwaysGetsTheSameAnswer() {
        PortalSurvey world = PortalSurvey.completePortalAt(SPLIT_BY_THE_GRID);
        Coord standing = new Coord(SPLIT_BY_THE_GRID.x() + 1, SPLIT_BY_THE_GRID.z() - 1);

        assertEquals(EndPortalAnchor.centreOf(standing, world),
            EndPortalAnchor.centreOf(standing, world));
    }

    // -------------------------------------------------------------------------
    // Portals that are not in good repair
    // -------------------------------------------------------------------------

    @Test
    void aPortalWithNoFramesIsStillOnePortal() {
        // An opening placed by an editor rather than lit with eyes. No ring to settle it, so the
        // centre is the candidate covering the most of the opening — which only the real one can
        // cover all of.
        Coord centre = new Coord(77_000, -412_000);
        PortalSurvey world = PortalSurvey.unframedPortalAt(centre);

        for (Coord offset : EndPortalShape.portalOffsets()) {
            Coord standing = new Coord(centre.x() + offset.x(), centre.z() + offset.z());
            assertEquals(centre, EndPortalAnchor.centreOf(standing, world),
                "an unframed portal must still answer with one centre");
        }
    }

    @Test
    void aRingSettlesAPortalSomebodyHasTakenBlocksOutOf() {
        // The ring is what makes this survivable: frames cannot be mined, so a portal missing half
        // its opening is still unmistakably one portal standing in one place.
        Coord centre = new Coord(-5_120, 63_488);
        PortalSurvey world = PortalSurvey.completePortalAt(centre)
            .without(centre,
                new Coord(centre.x() - 1, centre.z()),
                new Coord(centre.x(), centre.z() + 1));

        for (Coord offset : EndPortalShape.portalOffsets()) {
            Coord standing = new Coord(centre.x() + offset.x(), centre.z() + offset.z());
            if (!world.isPortalBlock(standing.x(), standing.z())) continue;
            assertEquals(centre, EndPortalAnchor.centreOf(standing, world),
                "a damaged opening inside an intact ring must still name its centre");
        }
    }

    @Test
    void nothingRecognisableStillAnswersWithSomething() {
        // Never null: the caller reflects whatever comes back, and a null here would be a crash in
        // a portal event rather than a traveller in a slightly odd place.
        Coord standing = new Coord(12, -34);
        Coord answer = EndPortalAnchor.centreOf(standing, PortalSurvey.completePortalAt());

        assertNotNull(answer);
        assertEquals(answer, EndPortalAnchor.centreOf(standing, PortalSurvey.completePortalAt()));
    }

    // -------------------------------------------------------------------------
    // Portals with company
    // -------------------------------------------------------------------------

    @Test
    void aPortalIsNotConfusedByTheOneNextToIt() {
        // Two openings four blocks apart share no block and no frame slot, and the ring around each
        // is complete, so every block of each names its own.
        Coord west = new Coord(1_000, 1_000);
        Coord east = new Coord(1_008, 1_000);
        PortalSurvey world = PortalSurvey.completePortalAt(west, east);

        for (Coord offset : EndPortalShape.portalOffsets()) {
            assertEquals(west, EndPortalAnchor.centreOf(
                new Coord(west.x() + offset.x(), west.z() + offset.z()), world));
            assertEquals(east, EndPortalAnchor.centreOf(
                new Coord(east.x() + offset.x(), east.z() + offset.z()), world));
        }
    }

    // -------------------------------------------------------------------------
    // The shape the anchor is built on
    // -------------------------------------------------------------------------

    @Test
    void aPortalBlockOffersOneCandidateCentrePerSlotItCouldFill() {
        Coord block = new Coord(-93, 4_021);
        var centres = EndPortalShape.candidateCentresOfOpening(block);

        assertEquals(9, centres.size());
        assertEquals(centres.size(), Set.copyOf(centres).size(), "nine slots, nine distinct centres");
        for (Coord centre : centres) {
            assertTrue(EndPortalShape.portalOffsets().contains(
                    new Coord(block.x() - centre.x(), block.z() - centre.z())),
                "the block lies in the opening of every centre offered for it");
        }
    }

    @Test
    void theRealCentreIsAlwaysAmongTheCandidates() {
        Coord centre = new Coord(640_000, -640_000);
        for (Coord offset : EndPortalShape.portalOffsets()) {
            Coord block = new Coord(centre.x() + offset.x(), centre.z() + offset.z());
            assertTrue(EndPortalShape.candidateCentresOfOpening(block).contains(centre),
                "a block at " + offset.key() + " must name the centre it belongs to");
        }
    }
}
