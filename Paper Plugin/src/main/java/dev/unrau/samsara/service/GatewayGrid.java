package dev.unrau.samsara.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The deterministic scatter of distributed wormhole gateways in the End.
 *
 * <p>The End is divided into square cells of {@code spacing} blocks, and each cell holds exactly one
 * gateway. <em>Where</em> in its cell is decided by hashing the cell's indices with the world's seed,
 * so the layout is irregular but reproducible: the same world always scatters its gateways the same
 * way, and no two worlds scatter them alike.
 *
 * <h2>Why the gateways are scattered rather than on a lattice</h2>
 *
 * <p>This used to put a gateway at the centre of every cell, which reads as reasonable and plays
 * badly. A gateway is a small structure, noticed from perhaps a hundred or two hundred blocks away,
 * so what matters is not gateways per square kilometre but whether one drifts into view along the
 * line a player actually flies. On a lattice that is decided entirely by the row a player happens to
 * be in: fly east along a row of nodes and you meet one every cell, fly east halfway between two rows
 * and you meet <em>nothing, ever</em>, however far you go. Two players doing the same thing get
 * opposite games, and neither can tell why.
 *
 * <p>Scattering removes the rows. Each cell's gateway is offset independently on both axes, so the
 * perpendicular distance from any straight course varies from cell to cell instead of being fixed for
 * the whole journey. Every heading now samples the same distribution, and a few cells of travel is
 * enough for one of those samples to come close.
 *
 * <p>The offset is confined to a window {@code spacing - minimumSeparation} wide, centred in the
 * cell. That is the one thing scatter needs to be told: without it two gateways either side of a cell
 * boundary can land a few blocks apart, which wastes a gateway and looks like a mistake. Keeping a
 * margin of {@code minimumSeparation / 2} inside each edge guarantees neighbours are always at least
 * {@code minimumSeparation} apart, and — because the window straddles the cell centre rather than its
 * corner — no gateway can ever land on End 0,0 or on either axis.
 *
 * <p><b>The separation is not free, and is the reason it is a good deal smaller than the spacing.</b>
 * A margin no gateway may occupy is also a band of coordinates no gateway can be seen from: a player
 * flying due east along the middle of one is {@code minimumSeparation / 2} from the nearest position
 * a gateway could possibly take, so a sight range shorter than that goes unrewarded no matter how far
 * they fly — the lattice's blind courses, narrowed but not gone. Keeping the separation small enough
 * that half of it is well inside any plausible sight range is what actually removes them.
 *
 * <p>Where a node <em>leads</em> is not this class's business — {@link WormholePairing} answers that.
 * This only decides where one stands, which is what makes the End crossable at all: without the
 * scatter, a traveller who arrived by End portal would have to fly to the central island to find
 * their first gateway.
 *
 * <p>Nothing is generated ahead of time — the End is infinite and almost all of it will never be
 * visited. {@link EndGatewayNetwork} materialises a node the first time a player comes near it, and
 * the build is idempotent, so a node is identical whether it was created today or three restarts
 * ago. That is what makes this survive a restart without persisting anything: the scatter is a
 * function, not a list.
 *
 * <p>Pure and Bukkit-free so the arithmetic can be tested directly.
 */
public final class GatewayGrid {

    /** SplitMix64's finalising constants — a cheap, well-tested avalanche. */
    private static final long MIX_A = 0xBF58476D1CE4E5B9L;
    private static final long MIX_B = 0x94D049BB133111EBL;

    /** Odd strides, so distinct cells never collide before the avalanche runs. */
    private static final long STRIDE_I = 0x9E3779B97F4A7C15L;
    private static final long STRIDE_J = 0x2545F4914F6CDD1DL;

    /** Distinct salts, so a cell's two offsets are independent of each other. */
    private static final long SALT_X = 0x632BE59BD9B4E019L;
    private static final long SALT_Z = 0xA24BAED4963EE407L;

    private final int spacing;
    private final int minimumSeparation;

    /** Width of the offset window inside a cell, {@code spacing - minimumSeparation}. */
    private final int jitter;

    /** Blocks kept clear inside each cell edge, {@code minimumSeparation / 2}. */
    private final int margin;

    private final int centreExclusionRadius;
    private final int limit;
    private final long seed;

    /**
     * @param spacing               blocks per cell; one gateway lives in each, so this is the mean
     *                              distance between neighbours
     * @param minimumSeparation     the closest two neighbouring gateways may come, which is what
     *                              stops a scatter clumping a pair against a shared cell edge. Must
     *                              leave at least one block of window, and at least two so the
     *                              scatter cannot reach an axis
     * @param centreExclusionRadius nodes within this distance of End 0,0 are skipped, leaving the
     *                              dragon island and its vanilla structures alone
     * @param limit                 maximum {@code |x|} or {@code |z|} a node may occupy
     * @param seed                  chooses which scatter this world uses. The same seed always lays
     *                              the gateways out in the same places
     */
    public GatewayGrid(int spacing, int minimumSeparation, int centreExclusionRadius, int limit,
                       long seed) {
        if (spacing < 16) {
            throw new IllegalArgumentException("spacing must be at least 16, got " + spacing);
        }
        if (minimumSeparation < 2) {
            throw new IllegalArgumentException("minimumSeparation must be at least 2, got "
                + minimumSeparation);
        }
        if (minimumSeparation >= spacing) {
            throw new IllegalArgumentException("minimumSeparation (" + minimumSeparation
                + ") must be smaller than spacing (" + spacing + ")");
        }
        if (centreExclusionRadius < 0) {
            throw new IllegalArgumentException("centreExclusionRadius must not be negative, got "
                + centreExclusionRadius);
        }
        if (limit <= spacing) {
            throw new IllegalArgumentException("limit must exceed spacing, got " + limit);
        }
        this.spacing = spacing;
        this.minimumSeparation = minimumSeparation;
        this.jitter = spacing - minimumSeparation;
        this.margin = minimumSeparation / 2;
        this.centreExclusionRadius = centreExclusionRadius;
        this.limit = limit;
        this.seed = seed;
    }

    /**
     * The node in cell {@code i, j}, whether or not it is eligible.
     *
     * <p>Its position is a pure function of the two indices and the seed, so it is the same on every
     * call, in every session, for the life of the world.
     */
    public Coord node(int i, int j) {
        long x = (long) i * spacing + margin + offset(i, j, SALT_X);
        long z = (long) j * spacing + margin + offset(i, j, SALT_Z);
        return new Coord((int) x, (int) z);
    }

    /**
     * Every eligible node whose position lies within {@code radius} blocks of the given point,
     * nearest first. Deterministic ordering, so the same approach always materialises the same
     * gateway first.
     */
    public List<Coord> nodesWithin(int x, int z, int radius) {
        if (radius < 0) return List.of();

        // A node sits anywhere inside its own cell, so a cell can contribute to the result from one
        // cell further out than its origin suggests. Widening the sweep by one cell each way costs
        // nothing and is what keeps a scattered node from being missed at the edge of the radius.
        int minI = (int) Math.floorDiv((long) x - radius, spacing) - 1;
        int maxI = (int) Math.floorDiv((long) x + radius, spacing) + 1;
        int minJ = (int) Math.floorDiv((long) z - radius, spacing) - 1;
        int maxJ = (int) Math.floorDiv((long) z + radius, spacing) + 1;

        long radiusSquared = (long) radius * radius;
        Coord from = new Coord(x, z);

        List<Coord> found = new ArrayList<>();
        for (int i = minI; i <= maxI; i++) {
            for (int j = minJ; j <= maxJ; j++) {
                Coord candidate = node(i, j);
                if (!isEligible(candidate)) continue;
                if (candidate.distanceSquaredTo(from) > radiusSquared) continue;
                found.add(candidate);
            }
        }
        // Position breaks ties, so two equidistant nodes are never ordered by iteration accident.
        found.sort(Comparator.comparingLong((Coord candidate) -> candidate.distanceSquaredTo(from))
            .thenComparingInt(Coord::x)
            .thenComparingInt(Coord::z));
        return found;
    }

    /**
     * The eligible node nearest a point, or null if the scatter has none within a few cells — which
     * only happens next to the excluded centre or beyond the world border.
     */
    public Coord nearest(int x, int z) {
        List<Coord> candidates = nodesWithin(x, z, spacing * 4);
        return candidates.isEmpty() ? null : candidates.getFirst();
    }

    /** True if a gateway may stand here: clear of the dragon island and inside the world border. */
    public boolean isEligible(Coord node) {
        if (Math.abs(node.x()) > limit || Math.abs(node.z()) > limit) return false;
        return node.distanceFromOrigin() >= centreExclusionRadius;
    }

    public int spacing() {
        return spacing;
    }

    public int minimumSeparation() {
        return minimumSeparation;
    }

    /** A cell's offset along one axis: uniform over {@code [0, jitter)} and stable for that cell. */
    private int offset(int i, int j, long salt) {
        long h = seed + salt + i * STRIDE_I + j * STRIDE_J;
        h = (h ^ (h >>> 30)) * MIX_A;
        h = (h ^ (h >>> 27)) * MIX_B;
        h = h ^ (h >>> 31);
        return Math.floorMod(h, jitter);
    }
}
