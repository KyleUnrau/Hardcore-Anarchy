package dev.unrau.samsara.service;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.EndPortalFrame;

/**
 * Names the portal a traveller stepped into, rather than the block they happened to step on.
 *
 * <h2>The bug this exists to stop</h2>
 *
 * <p>An End portal is a 3×3 opening, and a traveller entering it is standing in exactly one of nine
 * blocks — whichever one their feet were over. Routing from that block asks the reflection a
 * different question depending on where somebody stood, and the answers do not agree.
 *
 * <p>Grid snapping was supposed to make them agree, and for most portals it does: nine positions
 * three blocks across usually fall inside one 16-block cell. <b>Usually is not always.</b> A portal
 * whose opening straddles a cell boundary hands its blocks two different cells on that axis, and a
 * portal straddling one on <em>each</em> axis hands them four. The sites are 16 blocks apart, which
 * is one chunk, so the End grows a second arrival platform in the chunk next door — both of them
 * leading back to the same stronghold, because both are honest reflections of the same portal room.
 * <b>This was a real bug</b>, and it was not a rare one: of the 256 positions a portal centre can
 * take relative to the grid, <b>60 straddle a boundary</b>. Very nearly a quarter of the strongholds
 * on the server were going to do it.
 *
 * <p>Snapping cannot be made to fix this, and a wider grid makes it worse rather than better: the
 * boundary does not go away, it only moves, and every portal that lands on one is still split.
 * The question was wrong. What names a portal is not one of its blocks, snapped or otherwise — it is
 * <em>the portal</em>, and the portal has a centre.
 *
 * <h2>Finding the centre</h2>
 *
 * <p>A portal block says nothing about which of the nine slots it fills, so all nine candidate
 * centres are scored against the world and the best one wins:
 *
 * <ul>
 *   <li><b>A complete ring of twelve frames settles it outright.</b> The frames face inward, exactly
 *       as vanilla requires to light the portal in the first place, and no two portals can share a
 *       ring — so the candidate wearing one is the portal, and it is the same candidate from all
 *       nine blocks inside it.</li>
 *   <li><b>Failing that, the opening itself.</b> A portal placed by an editor may have no frames at
 *       all; the candidate covering the most portal blocks is still the true centre, since only it
 *       can cover all nine.</li>
 * </ul>
 *
 * <p>Ties break on the lowest coordinate, so even a shape this has never seen resolves the same way
 * every time. Determinism is the whole requirement here: a centre that wavered would put the
 * platform back exactly where it started.
 *
 * <p>The scoring is pure and reads the world through {@link Survey}, which is what lets the part
 * that matters be tested without a server attached.
 */
public final class EndPortalAnchor {

    /** How far from the traveller's own block to look for the portal they stepped into. */
    private static final int PORTAL_SEARCH_RADIUS = 1;

    /**
     * What a complete ring is worth. Larger than any opening can score, because a ring is not
     * evidence to be weighed against the opening — it is proof, and it ends the question.
     */
    private static final int COMPLETE_RING = 100;

    /** The world, as much of it as naming a portal needs. Implemented against Bukkit, or a test. */
    public interface Survey {

        /** True if an End portal block stands here, in the plane being read. */
        boolean isPortalBlock(int x, int z);

        /** True if an End portal frame stands here and faces this way. */
        boolean isFrameFacing(int x, int z, BlockFace facing);
    }

    private EndPortalAnchor() {
    }

    /**
     * The centre of the End portal a traveller is standing in, or their own position if they are
     * somehow not standing in one.
     *
     * <p>Their feet are inside a portal block, but a small neighbourhood is checked as well so
     * somebody clipping the edge of the opening is still recognised. Any block of the portal gives
     * the same answer, which is the entire point.
     *
     * <p>Must be called on the main thread. Everything read is within three blocks of the traveller
     * and therefore in a chunk they are already keeping loaded.
     */
    public static Coord centreOf(World world, Location standing) {
        Block portal = portalBlockNear(standing);
        if (portal == null) {
            // Nothing to anchor to — a portal event without a portal block. Route from the position
            // itself; snapping still collapses most of an opening onto one site, which is what this
            // did everywhere before there was an anchor at all.
            return new Coord(standing.getBlockX(), standing.getBlockZ());
        }
        return centreOf(new Coord(portal.getX(), portal.getZ()), surveyOf(world, portal.getY()));
    }

    /**
     * The centre of the portal this block belongs to. Pure: every world read goes through the
     * survey, and the same survey always gives the same answer.
     */
    public static Coord centreOf(Coord portalBlock, Survey survey) {
        Coord best = null;
        int bestScore = -1;

        for (Coord candidate : EndPortalShape.candidateCentresOfOpening(portalBlock)) {
            int score = score(candidate, survey);
            if (score > bestScore || (score == bestScore && precedes(candidate, best))) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    /**
     * How well a candidate centre is borne out by the world: a complete ring of inward-facing frames
     * if there is one, and the portal blocks filling the opening either way.
     */
    private static int score(Coord centre, Survey survey) {
        int opening = 0;
        for (Coord offset : EndPortalShape.portalOffsets()) {
            if (survey.isPortalBlock(centre.x() + offset.x(), centre.z() + offset.z())) opening++;
        }

        int frames = 0;
        for (Coord offset : EndPortalShape.frameOffsets()) {
            if (survey.isFrameFacing(centre.x() + offset.x(), centre.z() + offset.z(),
                EndPortalShape.facingAt(offset.x(), offset.z()))) {
                frames++;
            }
        }

        return frames == EndPortalShape.FRAME_COUNT ? COMPLETE_RING + opening : opening;
    }

    /** The tie-break: lowest x, then lowest z. Arbitrary, and fixed forever, which is what matters. */
    private static boolean precedes(Coord candidate, Coord best) {
        return candidate.x() != best.x() ? candidate.x() < best.x() : candidate.z() < best.z();
    }

    /**
     * The portal block a traveller is in. Their own block first, then the neighbourhood around it,
     * so a player caught on the edge of the opening still names the portal rather than falling
     * through to the unanchored path.
     *
     * <p>Feet, then a block down, then a block up: whichever is found settles the plane the survey
     * reads, and an End portal is flat, so that plane is the whole of the shape.
     */
    private static Block portalBlockNear(Location standing) {
        Block at = standing.getBlock();
        if (at.getType() == Material.END_PORTAL) return at;

        for (int dy : new int[] {0, -PORTAL_SEARCH_RADIUS, PORTAL_SEARCH_RADIUS}) {
            for (int dx = -PORTAL_SEARCH_RADIUS; dx <= PORTAL_SEARCH_RADIUS; dx++) {
                for (int dz = -PORTAL_SEARCH_RADIUS; dz <= PORTAL_SEARCH_RADIUS; dz++) {
                    Block candidate = at.getRelative(dx, dy, dz);
                    if (candidate.getType() == Material.END_PORTAL) return candidate;
                }
            }
        }
        return null;
    }

    /**
     * Reads one horizontal plane of the world.
     *
     * <p>An End portal is flat — the frames and the blocks they hold all stand at one height — so a
     * plane is the whole of what the shape needs, and a frame a block up is a different frame.
     *
     * <p>Positions in an unloaded chunk read as empty rather than loading it. Nothing here is ever
     * more than three blocks from a standing player, so this cannot happen in practice; what it
     * rules out is this class stalling the server on chunk generation from inside an event handler,
     * which is a worse thing to be wrong about than a portal with an unusual shape.
     */
    private static Survey surveyOf(World world, int y) {
        return new Survey() {
            @Override
            public boolean isPortalBlock(int x, int z) {
                return loaded(x, z) && world.getBlockAt(x, y, z).getType() == Material.END_PORTAL;
            }

            @Override
            public boolean isFrameFacing(int x, int z, BlockFace facing) {
                return loaded(x, z)
                    && world.getBlockAt(x, y, z).getBlockData() instanceof EndPortalFrame frame
                    && frame.getFacing() == facing;
            }

            private boolean loaded(int x, int z) {
                return world.isChunkLoaded(x >> 4, z >> 4);
            }
        };
    }
}
