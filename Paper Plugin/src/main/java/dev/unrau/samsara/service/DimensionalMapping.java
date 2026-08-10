package dev.unrau.samsara.service;

/**
 * The coordinate rule that connects the Overworld and the End.
 *
 * <p>Samsara has no capital and no universal spawn, so vanilla's habit of funnelling every End
 * portal in the world onto the central island cannot be used — it would hand the server the one thing
 * it is built to do without. One transform replaces it:
 *
 * <table>
 *   <caption>Dimensional transform</caption>
 *   <tr><th>Route</th><th>Rule</th></tr>
 *   <tr><td>Overworld &rarr; End</td><td>{@code (x, z) -> (-z, -x)}</td></tr>
 *   <tr><td>End &rarr; Overworld</td><td>{@code (x, z) -> (-z, -x)}</td></tr>
 * </table>
 *
 * <p>Sign inversion <em>and</em> a swap of the axes: the End is the Overworld reflected in the line
 * {@code z = -x}. Reflection in a line is its own inverse, which is the whole point — the gateway
 * standing at the reflection of a stronghold returns a traveller to that stronghold, and no record of
 * who travelled, or from where, is ever written down. Losing a file cannot lose someone's way home,
 * because there is no file.
 *
 * <p>The Nether is not part of this. Overworld &harr; Nether travel is vanilla in both directions,
 * including its eightfold scaling; the only thing this class knows about the Nether is how to read a
 * Nether position in Overworld terms, which the exile search needs after a death down there.
 *
 * <p>Pure and free of any Bukkit dependency: the same coordinates must produce the same destination
 * across restarts, world edits and plugin versions, and that is far easier to guarantee — and to
 * test — without a server attached.
 */
public final class DimensionalMapping {

    /** Vanilla's hard coordinate limit in the Overworld and the End. */
    public static final int VANILLA_LIMIT = 29_999_984;

    /** Blocks of Overworld covered by one block of Nether. */
    public static final int NETHER_SCALE = 8;

    /** The dimensions this mapping knows about, named without a Bukkit dependency. */
    public enum Realm {
        OVERWORLD,
        END,
        NETHER
    }

    private final int overworldLimit;
    private final int endLimit;
    private final int siteSpacing;

    /**
     * @param overworldLimit maximum {@code |x|} or {@code |z|} usable in the Overworld
     * @param endLimit       maximum usable in the End
     * @param siteSpacing    grid the End arrival point is snapped to. 1 disables snapping
     */
    public DimensionalMapping(int overworldLimit, int endLimit, int siteSpacing) {
        if (overworldLimit <= 0) {
            throw new IllegalArgumentException("overworldLimit must be positive, got " + overworldLimit);
        }
        if (endLimit <= 0) {
            throw new IllegalArgumentException("endLimit must be positive, got " + endLimit);
        }
        if (siteSpacing < 1) {
            throw new IllegalArgumentException("siteSpacing must be at least 1, got " + siteSpacing);
        }
        this.overworldLimit = overworldLimit;
        this.endLimit = endLimit;
        this.siteSpacing = siteSpacing;
    }

    /** Builds a mapping with vanilla limits, for tests and for callers with no world to inspect. */
    public static DimensionalMapping vanilla(int siteSpacing) {
        return new DimensionalMapping(VANILLA_LIMIT, VANILLA_LIMIT, siteSpacing);
    }

    /**
     * Where an Overworld End portal leads: the reflected position, snapped to the arrival grid.
     *
     * <p><b>Snapping is not what keeps one portal to one platform.</b> It was believed to be, and it
     * is not: an opening three blocks across straddles a cell boundary roughly a quarter of the
     * time, and its blocks then answer with two sites — or four, straddling one boundary on each
     * axis. What guarantees a single site is being asked about the portal rather than about a block
     * of it, which is {@link EndPortalAnchor}'s job and must be done before calling this.
     *
     * <p>What snapping still earns is a backstop for the one case the anchor cannot name a portal
     * for, where collapsing an opening onto a shared cell is better than nothing. It costs a return
     * column up to half a cell from the portal, which the return search is sized to cover.
     */
    public Coord overworldToEnd(int x, int z) {
        return new Coord(
            (int) clamp(snap(-(long) z), endLimit),
            (int) clamp(snap(-(long) x), endLimit)
        );
    }

    /**
     * Where a home gateway in the End leads. Not snapped: the gateway's site centre is already on
     * the arrival grid, and an unsnapped reflection is what makes the two transforms exact inverses
     * of one another.
     */
    public Coord endToOverworld(int x, int z) {
        return new Coord(
            (int) clamp(-(long) z, overworldLimit),
            (int) clamp(-(long) x, overworldLimit)
        );
    }

    /** Vanilla eightfold scaling, applied by the server itself; reproduced here only for reasoning. */
    public Coord netherToOverworld(int x, int z) {
        return new Coord(
            (int) clamp((long) x * NETHER_SCALE, overworldLimit),
            (int) clamp((long) z * NETHER_SCALE, overworldLimit)
        );
    }

    /**
     * Projects a position in any dimension into comparable Overworld coordinates.
     *
     * <p>Used when a distance in the Overworld has to be measured against something that happened
     * elsewhere — most importantly the exile search after a death in the End or the Nether, where the
     * raw coordinates are not on the same scale, or the same axes, as the Overworld's.
     */
    public Coord toOverworld(Realm realm, int x, int z) {
        return switch (realm) {
            case OVERWORLD -> new Coord((int) clamp(x, overworldLimit), (int) clamp(z, overworldLimit));
            case END -> endToOverworld(x, z);
            case NETHER -> netherToOverworld(x, z);
        };
    }

    /** Spacing of the End arrival grid, in blocks. */
    public int siteSpacing() {
        return siteSpacing;
    }

    /** Snaps to the centre of a grid cell, so the mapping is idempotent and never drifts. */
    private long snap(long value) {
        if (siteSpacing <= 1) return value;
        long cell = Math.floorDiv(value, siteSpacing);
        return cell * siteSpacing + siteSpacing / 2L;
    }

    private static long clamp(long value, int limit) {
        if (value > limit) return limit;
        if (value < -limit) return -limit;
        return value;
    }
}
