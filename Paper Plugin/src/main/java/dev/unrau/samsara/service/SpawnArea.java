package dev.unrau.samsara.service;

import dev.unrau.samsara.config.SpawnAreaShape;

import java.util.Random;
import java.util.logging.Logger;

/**
 * The band of coordinates a spawn is drawn from: a shape, a centre, and an inner and outer
 * distance. Resolved once per search from the configured distances and the world border, so every
 * candidate it produces is already known to be inside the playable world.
 *
 * <p>This class deliberately takes plain numbers rather than a {@code World} — it is the part of
 * spawn selection that has to be right, and it is unit tested without a server.
 *
 * <p>Resolution never fails. Nonsense distances, a border that excludes 0,0, a border smaller than
 * the configured minimum: each is logged and narrowed to something usable, because the alternative
 * is a player left standing at world spawn.
 */
public final class SpawnArea {

    /** Vanilla's hard coordinate limit. Beyond this the world simply does not generate. */
    public static final int WORLD_LIMIT = 29_999_984;

    /** Kept clear of the border wall so nobody spawns pressed against it. */
    private static final int BORDER_MARGIN = 16;

    private final SpawnAreaShape shape;
    private final int centerX;
    private final int centerZ;
    private final int min;
    private final int max;

    SpawnArea(SpawnAreaShape shape, int centerX, int centerZ, int min, int max) {
        this.shape = shape;
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.min = min;
        this.max = max;
    }

    /**
     * Builds the usable spawn band.
     *
     * @param borderCenterX world border centre X
     * @param borderCenterZ world border centre Z
     * @param borderSize    world border diameter in blocks
     * @param respectBorder false to ignore the border entirely (the world limit still applies)
     */
    public static SpawnArea resolve(SpawnAreaShape shape, int configMin, int configMax,
                                    double borderCenterX, double borderCenterZ, double borderSize,
                                    boolean respectBorder, Logger log) {
        // Square when nothing says otherwise, matching the configured default: the world is a
        // square, and a circle drawn inside it puts the corners out of reach for no reason.
        SpawnAreaShape effectiveShape = shape != null ? shape : SpawnAreaShape.SQUARE;

        int centerX = 0;
        int centerZ = 0;
        long reach = WORLD_LIMIT;

        if (respectBorder && borderSize > 0 && Double.isFinite(borderSize)) {
            long half = (long) Math.floor(borderSize / 2.0) - BORDER_MARGIN;
            if (half < 1) {
                log.warning("[Samsara] World border is only " + (long) borderSize
                    + " blocks across; ignoring it when choosing spawns.");
            } else {
                long offset = Math.max(Math.abs((long) borderCenterX), Math.abs((long) borderCenterZ));
                // The largest origin-centred box that still fits inside the border.
                long fromOrigin = half - offset;
                if (fromOrigin >= 1) {
                    reach = Math.min(reach, fromOrigin);
                } else {
                    // The border does not surround 0,0, so measure distances from its centre instead.
                    centerX = (int) Math.max(-WORLD_LIMIT, Math.min(WORLD_LIMIT, (long) borderCenterX));
                    centerZ = (int) Math.max(-WORLD_LIMIT, Math.min(WORLD_LIMIT, (long) borderCenterZ));
                    reach = Math.min(half, WORLD_LIMIT - Math.max(Math.abs((long) centerX), Math.abs((long) centerZ)));
                    log.warning("[Samsara] World border is centred on " + centerX + "," + centerZ
                        + " and does not contain 0,0; spawn distances are measured from the border centre.");
                }
            }
        }

        if (reach < 1) reach = 1;

        int max = clampToInt(Math.min(sanitise(configMax, log, "max"), reach));
        if (max < 1) max = 1;

        int min = clampToInt(sanitise(configMin, log, "min"));
        if (min > max) {
            log.warning("[Samsara] Spawn minimum distance " + min + " exceeds the usable maximum "
                + max + "; using the whole area out to " + max + " instead.");
            min = 0;
        }

        // Worth saying only when the border actually overrules the configured maximum. A maximum set
        // to the world limit — the default — always loses the last few blocks to BORDER_MARGIN, and
        // this is resolved once per search, so reporting that would be a log line per death.
        if (configMax - max > BORDER_MARGIN) {
            log.info("[Samsara] Spawn maximum distance narrowed from " + configMax + " to " + max
                + " to stay inside the world border.");
        }

        return new SpawnArea(effectiveShape, centerX, centerZ, min, max);
    }

    private static long sanitise(int configured, Logger log, String label) {
        if (configured < 0) {
            log.warning("[Samsara] Negative spawn " + label + " distance " + configured + "; treating it as 0.");
            return 0;
        }
        return configured;
    }

    private static int clampToInt(long value) {
        return (int) Math.max(0, Math.min(WORLD_LIMIT, value));
    }

    /**
     * Picks a random point in the band, distributed uniformly by area rather than by distance —
     * without that correction most spawns would cluster near the inner edge.
     *
     * @return a two-element {x, z} array, always inside the world border
     */
    public int[] randomPoint(Random random) {
        // Both shapes grow their area with the square of the distance, so the same inverse-CDF
        // sample gives a uniform spread over either ring.
        double minSq = (double) min * min;
        double maxSq = (double) max * max;
        double r = Math.sqrt(random.nextDouble() * (maxSq - minSq) + minSq);

        double x;
        double z;
        if (shape == SpawnAreaShape.SQUARE) {
            // Land on one of the four edges of the square at distance r, uniformly along it.
            double along = (random.nextDouble() * 2.0 - 1.0) * r;
            switch (random.nextInt(4)) {
                case 0 -> { x = r;     z = along; }
                case 1 -> { x = -r;    z = along; }
                case 2 -> { x = along; z = r;     }
                default -> { x = along; z = -r;   }
            }
        } else {
            double angle = random.nextDouble() * 2 * Math.PI;
            x = r * Math.cos(angle);
            z = r * Math.sin(angle);
        }

        return new int[]{
            clampCoordinate(centerX + Math.round(x)),
            clampCoordinate(centerZ + Math.round(z))
        };
    }

    private static int clampCoordinate(long value) {
        return (int) Math.max(-WORLD_LIMIT, Math.min(WORLD_LIMIT, value));
    }

    public SpawnAreaShape getShape() { return shape; }
    public int getCenterX()          { return centerX; }
    public int getCenterZ()          { return centerZ; }
    public int getMin()              { return min; }
    public int getMax()              { return max; }

    @Override
    public String toString() {
        return shape.name().toLowerCase() + " " + min + ".." + max
            + " from " + centerX + "," + centerZ;
    }
}
