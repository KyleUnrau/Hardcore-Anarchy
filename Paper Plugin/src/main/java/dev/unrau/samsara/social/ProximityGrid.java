package dev.unrau.samsara.social;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Finds which players are standing near which, without comparing everybody to everybody.
 *
 * <p>The naive version of this is a double loop, and on a busy server it is run several times a
 * minute for the rest of the server's life. Bucketing by the radius itself makes it linear in the
 * number of players instead: two players can only be within one radius of each other if they are in
 * the same cell or in one of its eight neighbours, so nothing further away is ever measured.
 *
 * <p>Only the horizontal axes are bucketed. Vertical spread inside a cell is real but small — two
 * players cannot be more than a few hundred blocks apart in Y — so the cheap 3×3 sweep is left to
 * find the candidates and the exact three-dimensional distance decides.
 *
 * <p>Pure, and knows nothing about Bukkit: what it works on is positions with names, which is what
 * makes the pairing rule testable without a server.
 */
public final class ProximityGrid {

    /** Where one player is. Worlds are separated before this is called. */
    public record Position(UUID id, double x, double y, double z) {}

    /**
     * Two players close enough to matter, and how far apart they actually were.
     *
     * <p>The distance is reported rather than thrown away because "near" is not a yes or no: sharing
     * a workbench and standing at opposite ends of a field are both inside any radius wide enough to
     * mean the first, and the caller is the one that decides what each is worth.
     */
    public record Near(PairKey pair, double distance) {}

    private ProximityGrid() {
    }

    /**
     * Every pair of the given positions within {@code radius} of each other, each pair once, with
     * the distance between them.
     *
     * @param radius in blocks; zero or less pairs nobody
     */
    public static List<Near> pairsWithin(Collection<Position> positions, double radius) {
        List<Near> pairs = new ArrayList<>();
        if (radius <= 0 || positions.size() < 2) return pairs;

        Map<Long, List<Position>> cells = new HashMap<>();
        for (Position position : positions) {
            cells.computeIfAbsent(cellOf(position, radius), key -> new ArrayList<>()).add(position);
        }

        double limit = radius * radius;
        for (Position position : positions) {
            long bx = Math.floorDiv((long) Math.floor(position.x()), (long) Math.ceil(radius));
            long bz = Math.floorDiv((long) Math.floor(position.z()), (long) Math.ceil(radius));

            for (long dx = -1; dx <= 1; dx++) {
                for (long dz = -1; dz <= 1; dz++) {
                    List<Position> cell = cells.get(key(bx + dx, bz + dz));
                    if (cell == null) continue;

                    for (Position other : cell) {
                        // Each pair is looked at from both ends, because the neighbourhood of a cell
                        // contains the cells whose neighbourhoods contain it. One ordering wins, and
                        // the pair is recorded once.
                        if (position.id().compareTo(other.id()) >= 0) continue;
                        double squared = distanceSquared(position, other);
                        if (squared <= limit) {
                            pairs.add(new Near(PairKey.of(position.id(), other.id()),
                                Math.sqrt(squared)));
                        }
                    }
                }
            }
        }
        return pairs;
    }

    private static double distanceSquared(Position a, Position b) {
        double dx = a.x() - b.x();
        double dy = a.y() - b.y();
        double dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz;
    }

    private static long cellOf(Position position, double radius) {
        long size = (long) Math.ceil(radius);
        return key(Math.floorDiv((long) Math.floor(position.x()), size),
            Math.floorDiv((long) Math.floor(position.z()), size));
    }

    private static long key(long x, long z) {
        return (x << 32) ^ (z & 0xffffffffL);
    }
}
