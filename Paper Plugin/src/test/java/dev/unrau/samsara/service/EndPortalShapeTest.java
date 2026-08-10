package dev.unrau.samsara.service;

import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shape is copied from vanilla rather than derived, so these tests are the copy being checked.
 * Getting it wrong in either direction is bad: a shape too loose lights portals vanilla would not,
 * and a shape too strict leaves the last eye to the server — and the roar goes out to the whole map
 * again, which is the thing this all exists to stop.
 */
class EndPortalShapeTest {

    @Test
    void theRingIsTwelveFramesWithNoCorners() {
        List<Coord> offsets = EndPortalShape.frameOffsets();

        assertEquals(EndPortalShape.FRAME_COUNT, offsets.size());
        assertEquals(offsets.size(), Set.copyOf(offsets).size(), "no position is listed twice");
        for (Coord offset : offsets) {
            assertTrue(Math.abs(offset.x()) <= 2 && Math.abs(offset.z()) <= 2, "inside the 5x5");
            assertTrue(Math.abs(offset.x()) == 2 || Math.abs(offset.z()) == 2, "on an edge");
            assertTrue(Math.abs(offset.x()) != 2 || Math.abs(offset.z()) != 2, "not at a corner");
        }
    }

    @Test
    void theOpeningIsThreeByThreeAndNoFrameStandsInIt() {
        List<Coord> portal = EndPortalShape.portalOffsets();

        assertEquals(9, portal.size());
        assertEquals(portal.size(), Set.copyOf(portal).size());
        for (Coord offset : portal) {
            assertTrue(Math.abs(offset.x()) <= 1 && Math.abs(offset.z()) <= 1);
            assertNull(EndPortalShape.facingAt(offset.x(), offset.z()),
                "a portal block and a frame cannot want the same position");
        }
    }

    @Test
    void everyFrameFacesTheCentre() {
        // The frames on the north edge look south across the opening, and so on around the ring. A
        // frame turned outward does not complete a portal in vanilla and must not complete one here.
        assertEquals(BlockFace.SOUTH, EndPortalShape.facingAt(0, -2));
        assertEquals(BlockFace.NORTH, EndPortalShape.facingAt(0, 2));
        assertEquals(BlockFace.EAST, EndPortalShape.facingAt(-2, 0));
        assertEquals(BlockFace.WEST, EndPortalShape.facingAt(2, 0));

        for (Coord offset : EndPortalShape.frameOffsets()) {
            BlockFace facing = EndPortalShape.facingAt(offset.x(), offset.z());
            assertNotNull(facing);
            // One step in the direction it faces is one step closer to the centre.
            long before = offset.distanceSquaredTo(new Coord(0, 0));
            long after = new Coord(offset.x() + facing.getModX(), offset.z() + facing.getModZ())
                .distanceSquaredTo(new Coord(0, 0));
            assertTrue(after < before, "a frame at " + offset.key() + " faces " + facing + ", away from the centre");
        }
    }

    @Test
    void nothingOutsideTheRingIsAFrameSlot() {
        assertNull(EndPortalShape.facingAt(0, 0), "the centre of the opening");
        assertNull(EndPortalShape.facingAt(2, 2), "a corner of the ring");
        assertNull(EndPortalShape.facingAt(-2, -2), "the opposite corner");
        assertNull(EndPortalShape.facingAt(3, 0), "a block beyond the ring");
        assertNull(EndPortalShape.facingAt(0, -3), "and beyond the other edge");
    }

    @Test
    void aFrameOffersOneCandidateCentrePerSlotItCouldOccupy() {
        Coord frame = new Coord(104, -57);
        List<Coord> centres = EndPortalShape.candidateCentres(frame);

        assertEquals(EndPortalShape.FRAME_COUNT, centres.size());
        assertEquals(centres.size(), Set.copyOf(centres).size(), "twelve slots, twelve distinct centres");
        for (Coord centre : centres) {
            assertTrue(EndPortalShape.frameOffsets().contains(
                    new Coord(frame.x() - centre.x(), frame.z() - centre.z())),
                "the frame stands in the ring of every centre offered for it");
        }
    }

    @Test
    void theRealCentreIsAlwaysAmongTheCandidates() {
        // What the listener depends on: whichever of the twelve slots a player clicks, the portal
        // that eye would finish is one of the centres it gets back.
        Coord centre = new Coord(-1_240, 3_016);
        for (Coord offset : EndPortalShape.frameOffsets()) {
            Coord frame = new Coord(centre.x() + offset.x(), centre.z() + offset.z());
            assertTrue(EndPortalShape.candidateCentres(frame).contains(centre),
                "a frame at " + offset.key() + " must name the centre it belongs to");
        }
    }
}
