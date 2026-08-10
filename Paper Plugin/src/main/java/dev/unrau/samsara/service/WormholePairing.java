package dev.unrau.samsara.service;

/**
 * Which End gateway a wormhole comes out of.
 *
 * <p>Every End gateway that is not one of the plugin's home gateways is a wormhole: step into it and
 * you are thrown to a partner cell somewhere else in the End, typically millions of blocks away.
 * Step into the gateway waiting there and you come back. The End stops being a place you cross and
 * becomes a place you jump around inside.
 *
 * <h2>Why this is arithmetic and not a saved list</h2>
 *
 * <p>The obvious implementation is a file of gateway pairs. That file can be lost, corrupted, or
 * fall out of step with a world that was edited or rolled back — and every one of those failures
 * strands a player, because the gateway in front of them no longer knows where it goes.
 *
 * <p>So the pairing is computed instead. Cell indices are run through a seeded
 * <a href="https://en.wikipedia.org/wiki/Feistel_cipher">Feistel</a> permutation {@code P}, and the
 * partner of a cell {@code n} is
 *
 * <pre>{@code   partner(n) = P⁻¹( P(n) XOR 1 )}</pre>
 *
 * <p>Two properties fall straight out of that, as identities rather than as things to remember:
 *
 * <ul>
 *   <li><b>Wormholes are two-way.</b> {@code partner(partner(n)) == n}, because the two XORs cancel:
 *       {@code P⁻¹(P(P⁻¹(P(n) ^ 1)) ^ 1) = P⁻¹(P(n) ^ 1 ^ 1) = n}. A pairing is never half-built and
 *       never disagrees with itself from the far end.</li>
 *   <li><b>No gateway leads to itself.</b> {@code P(n) ^ 1 != P(n)} and {@code P⁻¹} is injective, so
 *       {@code partner(n) != n} for every cell. There are no dud gateways.</li>
 * </ul>
 *
 * <p>Because {@code P} is a keyed pseudorandom permutation, flipping that one bit of the
 * <em>ciphertext</em> lands the partner in an unrelated part of the End: neighbouring gateways go to
 * wildly different places, and the destination cannot be worked out by eye.
 *
 * <h2>Why the network covers the whole End</h2>
 *
 * <p>Those identities are about <em>cells</em>, and they only help a player if the cell they are
 * standing in is one the network actually has. A position past the edge of the index space has to be
 * folded onto some cell that exists, and every folded position shares that cell's single
 * destination — so the return trip comes back to the cell, millions of blocks from where the
 * traveller set off. A network smaller than the End is therefore not a smaller network, it is a
 * broken one.
 *
 * <p>The old implementation demanded a power-of-two number of cells per axis, because a Feistel
 * network permutes a fixed number of <em>bits</em>. Vanilla's coordinate limit is not a power of two
 * multiple of any sane cell size, so that constraint made covering the End impossible: the reach had
 * to round down, and everything past it folded.
 *
 * <p>So the domain is now exactly the cells that exist, whatever number that is, and the
 * power-of-two permutation is fitted to it by
 * <a href="https://en.wikipedia.org/wiki/Format-preserving_encryption">cycle walking</a> — encrypt
 * into the enclosing power-of-two space, and keep encrypting while the result lands outside the real
 * domain. Because {@code P} is a permutation of the enclosing space, the orbit of any cell returns
 * to it, so the walk always terminates and the result is a permutation of the real domain. Both
 * identities above survive unchanged, and folding is now confined to coordinates beyond the world
 * border, where nobody can stand.
 *
 * <p>Pure and Bukkit-free, so the identities above can be tested directly rather than inferred from
 * a running server.
 */
public final class WormholePairing {

    /**
     * Feistel rounds. Four is the standard point at which a Feistel network with a strong round
     * function is a good pseudorandom permutation; the security argument is irrelevant here, the
     * mixing is not.
     */
    private static final int ROUNDS = 4;

    /** SplitMix64's finalising constants — a cheap, well-tested avalanche. */
    private static final long MIX_A = 0xBF58476D1CE4E5B9L;
    private static final long MIX_B = 0x94D049BB133111EBL;
    private static final long GOLDEN = 0x9E3779B97F4A7C15L;

    private final int cellSize;
    private final int halfCells;

    /** Cell indices per axis, {@code 2 * halfCells}. */
    private final long cellsPerAxis;

    /** The real domain: every cell in the network, always even so {@code XOR 1} stays inside it. */
    private final long cells;

    private final int halfBits;
    private final long halfMask;
    private final long seed;

    /**
     * @param cellSize  blocks per cell. Gateways in the same cell share a destination, and a round
     *                  trip lands within half a cell of where it started, so this doubles as the
     *                  precision of the network
     * @param halfCells cells from the origin to the edge of the network, per axis. Any value of at
     *                  least two; the permutation is fitted to the domain rather than the other way
     *                  round, so the network can be sized to the world instead of to a power of two
     * @param seed      chooses which of the astronomically many pairings this world uses. Changing it
     *                  repoints every wormhole in the End
     */
    public WormholePairing(int cellSize, int halfCells, long seed) {
        if (cellSize < 1) {
            throw new IllegalArgumentException("cellSize must be at least 1, got " + cellSize);
        }
        if (halfCells < 2) {
            throw new IllegalArgumentException("halfCells must be at least 2, got " + halfCells);
        }
        this.cellSize = cellSize;
        this.halfCells = halfCells;
        // Index space per axis is [-halfCells, halfCells), so 2 * halfCells values.
        this.cellsPerAxis = 2L * halfCells;
        this.cells = cellsPerAxis * cellsPerAxis;
        // Equal Feistel halves, so the enclosing space is 2^(2 * halfBits) and is never smaller than
        // the domain. It overshoots by at most 4x, which is the expected cost of one cycle walk.
        this.halfBits = (ceilLog2(cells) + 1) / 2;
        this.halfMask = (1L << halfBits) - 1;
        this.seed = seed;
    }

    /**
     * Builds a pairing covering {@code radius} blocks in every direction.
     *
     * <p>The reach is rounded down to a whole number of cells and no further, so what is left outside
     * the network is a sliver narrower than one cell. That matters more than it sounds: a coordinate
     * outside the network folds onto the edge, and a folded coordinate does not come back.
     * {@code radius} should therefore be the world border, not a smaller number — the plugin has no
     * use for a wormhole network that does not reach everywhere a player can stand.
     */
    public static WormholePairing covering(int cellSize, int radius, long seed) {
        if (cellSize < 1) {
            throw new IllegalArgumentException("cellSize must be at least 1, got " + cellSize);
        }
        return new WormholePairing(cellSize, Math.max(2, radius / cellSize), seed);
    }

    /**
     * Where the wormhole at this position comes out: the centre of the partner cell.
     *
     * <p>The result is a cell centre rather than a gateway position, because the gateway at the far
     * end may not exist yet — it is built on arrival. That is also what makes the round trip land
     * where it should: both ends of a pairing agree on a cell, not on a block.
     */
    public Coord partnerOf(int x, int z) {
        long index = packedIndex(cellIndex(x), cellIndex(z));

        // XOR 1 stays inside the domain because the domain size is even, so it pairs 0 with 1, 2
        // with 3, and so on with nothing left over.
        long paired = decryptWithinDomain(encryptWithinDomain(index) ^ 1L);

        return centreOfCell(unpackX(paired), unpackZ(paired));
    }

    /** The centre of the cell containing this position — where a wormhole arriving here lands. */
    public Coord cellCentreOf(int x, int z) {
        return centreOfCell(cellIndex(x), cellIndex(z));
    }

    /** Blocks from the origin to the edge of the network, per axis. */
    public int reach() {
        return (int) Math.min(Integer.MAX_VALUE, (long) halfCells * cellSize);
    }

    public int cellSize() {
        return cellSize;
    }

    // -------------------------------------------------------------------------
    // Cells
    // -------------------------------------------------------------------------

    /**
     * The cell index containing a coordinate, folded into the network's range.
     *
     * <p>Folding costs a traveller their way home — every folded coordinate shares one cell, so the
     * return trip comes back to that cell rather than to them. It is tolerated here only because the
     * caller sizes the network to the world border, which puts the folded region entirely outside
     * anywhere a player can be.
     */
    private int cellIndex(int coordinate) {
        long cell = Math.floorDiv((long) coordinate, cellSize);
        return (int) Math.max(-halfCells, Math.min(halfCells - 1L, cell));
    }

    private Coord centreOfCell(int i, int j) {
        return new Coord(
            (int) ((long) i * cellSize + cellSize / 2L),
            (int) ((long) j * cellSize + cellSize / 2L)
        );
    }

    // -------------------------------------------------------------------------
    // The permutation
    // -------------------------------------------------------------------------

    /** Packs two signed cell indices into one index in {@code [0, cells)}. */
    private long packedIndex(int i, int j) {
        return (i + (long) halfCells) * cellsPerAxis + (j + (long) halfCells);
    }

    private int unpackX(long packed) {
        return (int) (packed / cellsPerAxis) - halfCells;
    }

    private int unpackZ(long packed) {
        return (int) (packed % cellsPerAxis) - halfCells;
    }

    /**
     * The Feistel permutation restricted to the real domain, by cycle walking.
     *
     * <p>{@link #encrypt} permutes the enclosing power-of-two space, which is larger than the domain.
     * Re-encrypting until the result lands back inside it yields a permutation of the domain itself:
     * the orbit of a domain member under a permutation is a cycle through that member, so the walk
     * always comes home, and it does so after fewer than four steps on average.
     */
    private long encryptWithinDomain(long index) {
        long value = index;
        do {
            value = encrypt(value);
        } while (value >= cells);
        return value;
    }

    /**
     * The inverse: walking backwards visits exactly the same out-of-domain values in reverse, so it
     * takes the same number of steps and lands on the index {@link #encryptWithinDomain} started from.
     */
    private long decryptWithinDomain(long index) {
        long value = index;
        do {
            value = decrypt(value);
        } while (value >= cells);
        return value;
    }

    private long encrypt(long value) {
        long left = (value >>> halfBits) & halfMask;
        long right = value & halfMask;
        for (int round = 0; round < ROUNDS; round++) {
            long next = left ^ f(right, round);
            left = right;
            right = next;
        }
        return (left << halfBits) | right;
    }

    private long decrypt(long value) {
        long left = (value >>> halfBits) & halfMask;
        long right = value & halfMask;
        for (int round = ROUNDS - 1; round >= 0; round--) {
            long previous = right ^ f(left, round);
            right = left;
            left = previous;
        }
        return (left << halfBits) | right;
    }

    /** The round function: any function at all keeps the network invertible, so this only mixes. */
    private long f(long half, int round) {
        long h = half + seed + (round + 1L) * GOLDEN;
        h = (h ^ (h >>> 30)) * MIX_A;
        h = (h ^ (h >>> 27)) * MIX_B;
        h = h ^ (h >>> 31);
        return h & halfMask;
    }

    /** Bits needed to hold every value below {@code count}, for {@code count} of at least two. */
    private static int ceilLog2(long count) {
        return 64 - Long.numberOfLeadingZeros(count - 1);
    }
}
