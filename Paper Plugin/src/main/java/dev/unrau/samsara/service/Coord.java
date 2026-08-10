package dev.unrau.samsara.service;

/**
 * A horizontal block position — the only thing the dimensional transforms actually move.
 *
 * <p>Every transform in {@link DimensionalMapping} maps a {@code Coord} in one dimension to a
 * {@code Coord} in another. Y is deliberately absent: height is a question about the terrain at a
 * destination, answered separately once the chunks are loaded, not something a coordinate rule can
 * decide.
 */
public record Coord(int x, int z) {

    /** Stable identifier used in logs, admin output and gateway tags. */
    public String key() {
        return x + "," + z;
    }

    /** Squared horizontal distance to another position — avoids a square root in comparisons. */
    public long distanceSquaredTo(Coord other) {
        long dx = (long) x - other.x;
        long dz = (long) z - other.z;
        return dx * dx + dz * dz;
    }

    /** Distance from the origin, used for central-island exclusion checks. */
    public double distanceFromOrigin() {
        return Math.hypot((double) x, (double) z);
    }
}
