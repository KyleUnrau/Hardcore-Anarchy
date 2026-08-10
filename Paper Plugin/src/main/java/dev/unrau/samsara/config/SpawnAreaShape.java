package dev.unrau.samsara.config;

/**
 * How the min/max distances from 0,0 are measured when picking a spawn.
 *
 * <p>The difference only matters near the edge of the world. With a max of 30,000,000:
 * {@link #CIRCLE} never places anyone beyond that radius, so the corners of the map
 * (29m, 29m — a radius of ~41m) are unreachable. {@link #SQUARE} measures each axis
 * independently, so the whole square out to the corners is in play.
 *
 * <p>{@link #SQUARE} is the default, because at the default maximum the max <em>is</em> the edge
 * of the world and there is no reason to fence off most of it.
 */
public enum SpawnAreaShape {

    /** Distances are radii: sqrt(x² + z²). */
    CIRCLE,

    /** Distances are per-axis: max(|x|, |z|). Reaches the corners of the world border. */
    SQUARE;

    /**
     * Parses a configured value. A typo is reported rather than guessed at, and never stops a spawn
     * from being chosen: the caller logs it and substitutes the default.
     *
     * @return the parsed shape, or null if the value was not recognised
     */
    public static SpawnAreaShape parse(String raw) {
        if (raw == null) return null;
        return switch (raw.trim().toLowerCase()) {
            case "circle", "circular", "radial", "round" -> CIRCLE;
            case "square", "box", "rectangle", "rect" -> SQUARE;
            default -> null;
        };
    }
}
