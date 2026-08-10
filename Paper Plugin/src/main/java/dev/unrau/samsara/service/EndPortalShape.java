package dev.unrau.samsara.service;

import org.bukkit.block.BlockFace;

import java.util.ArrayList;
import java.util.List;

/**
 * The geometry of a stronghold End portal: twelve frames in a ring, nine portal blocks inside it.
 *
 * <p>Vanilla expresses this as a block pattern searched for in the world. The plugin needs the same
 * answer for a different reason — it takes over the moment a portal is completed, so that the roar
 * that follows can be played to the people standing there rather than to the entire server — and
 * pattern searching is not exposed to plugins. This is that shape written out directly, as offsets
 * from the block at the centre of the portal.
 *
 * <p>The ring is the 5×5 square around the centre with its corners and its interior removed: four
 * runs of three frames, each run facing inward across the opening. Facing inward is not decoration,
 * it is part of the shape — a frame turned the wrong way does not complete a portal in vanilla and
 * must not complete one here.
 *
 * <p>Nothing in here touches the world, which is what makes the shape testable on its own.
 */
public final class EndPortalShape {

    /** How far out from the centre the ring of frames stands. */
    private static final int FRAME_REACH = 2;

    /** How far out from the centre the portal itself reaches: a 3×3 opening. */
    private static final int PORTAL_REACH = 1;

    /** Frames in a completed portal. Four runs of three, corners left out. */
    public static final int FRAME_COUNT = 12;

    private EndPortalShape() {
    }

    /**
     * Every position a frame stands in, relative to the centre of the portal.
     *
     * <p>All twelve lie in the same horizontal plane as the centre, which is why height never
     * appears here: an End portal is flat, and a frame one block up is a different frame.
     */
    public static List<Coord> frameOffsets() {
        List<Coord> offsets = new ArrayList<>(FRAME_COUNT);
        for (int dx = -FRAME_REACH; dx <= FRAME_REACH; dx++) {
            for (int dz = -FRAME_REACH; dz <= FRAME_REACH; dz++) {
                if (isFrameOffset(dx, dz)) offsets.add(new Coord(dx, dz));
            }
        }
        return offsets;
    }

    /** The nine positions the portal blocks fill, relative to the centre. */
    public static List<Coord> portalOffsets() {
        List<Coord> offsets = new ArrayList<>(9);
        for (int dx = -PORTAL_REACH; dx <= PORTAL_REACH; dx++) {
            for (int dz = -PORTAL_REACH; dz <= PORTAL_REACH; dz++) {
                offsets.add(new Coord(dx, dz));
            }
        }
        return offsets;
    }

    /**
     * The way a frame in this position must face, or null if no frame belongs there at all.
     *
     * <p>Always inward, towards the centre — the frames on the north edge face south, and so on
     * around the ring.
     */
    public static BlockFace facingAt(int dx, int dz) {
        if (!isFrameOffset(dx, dz)) return null;
        if (dx == -FRAME_REACH) return BlockFace.EAST;
        if (dx == FRAME_REACH) return BlockFace.WEST;
        if (dz == -FRAME_REACH) return BlockFace.SOUTH;
        return BlockFace.NORTH;
    }

    /**
     * Every portal centre a frame at this position could belong to — one per slot in the ring.
     *
     * <p>A single frame block says nothing about which of the twelve slots it occupies, so a caller
     * asking "did that eye just finish a portal?" has twelve candidates to rule out and no way to
     * narrow them down beforehand. Reading the ring around each is cheap; guessing is not possible.
     */
    public static List<Coord> candidateCentres(Coord frame) {
        List<Coord> centres = new ArrayList<>(FRAME_COUNT);
        for (Coord offset : frameOffsets()) {
            centres.add(new Coord(frame.x() - offset.x(), frame.z() - offset.z()));
        }
        return centres;
    }

    /**
     * Every portal centre a <em>portal block</em> at this position could belong to — one per block
     * of the 3×3 opening.
     *
     * <p>The same problem as {@link #candidateCentres} and the same answer, asked from the inside
     * rather than from the ring. A traveller stands in one of nine blocks and the portal they are
     * standing in has to be named the same way whichever of the nine it is, so the nine candidates
     * are enumerated and {@link EndPortalAnchor} reads the world to settle which is real.
     */
    public static List<Coord> candidateCentresOfOpening(Coord portalBlock) {
        List<Coord> centres = new ArrayList<>(9);
        for (Coord offset : portalOffsets()) {
            centres.add(new Coord(portalBlock.x() - offset.x(), portalBlock.z() - offset.z()));
        }
        return centres;
    }

    /** True if a frame belongs at this offset: on one of the four edges, but not at a corner. */
    private static boolean isFrameOffset(int dx, int dz) {
        int ax = Math.abs(dx);
        int az = Math.abs(dz);
        return (ax == FRAME_REACH && az <= PORTAL_REACH) || (az == FRAME_REACH && ax <= PORTAL_REACH);
    }
}
