package dev.unrau.samsara.service;

import dev.unrau.samsara.config.PluginConfig;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;

import java.util.Set;

/**
 * Stateless utility that evaluates whether a given loaded-chunk location is
 * safe enough for an exile spawn. "Safe" means the player won't be
 * immediately killed — not necessarily comfortable.
 */
public class SafeLocationFinder {

    // Biomes that count as ocean for avoidOcean filter
    private static final Set<Biome> OCEAN_BIOMES = Set.of(
        Biome.OCEAN,
        Biome.DEEP_OCEAN,
        Biome.COLD_OCEAN,
        Biome.DEEP_COLD_OCEAN,
        Biome.FROZEN_OCEAN,
        Biome.DEEP_FROZEN_OCEAN,
        Biome.LUKEWARM_OCEAN,
        Biome.DEEP_LUKEWARM_OCEAN,
        Biome.WARM_OCEAN
    );

    /**
     * How far {@link #findAnyLanding} may walk down from the height map looking for real ground.
     * Deep enough to fall through the tallest canopy, shallow enough that a column reading as
     * "nothing here" never resolves to somewhere far below the surface.
     */
    private static final int CANOPY_DEPTH = 24;

    private final PluginConfig config;

    public SafeLocationFinder(PluginConfig config) {
        this.config = config;
    }

    /**
     * Given a world and integer X/Z coordinates (chunk already loaded), find the
     * highest safe surface Y and return a spawn Location, or null if unsafe.
     */
    public Location findSafeY(World world, int x, int z) {
        // Biome check doesn't require chunk load; do it first as cheap filter
        if (config.isAvoidOcean()) {
            // Sample biome at approximate surface height
            Biome biome = world.getBiome(x, 64, z);
            if (OCEAN_BIOMES.contains(biome)) return null;
        }

        int highestY = world.getHighestBlockYAt(x, z);

        // getHighestBlockYAt returns the Y of the topmost non-air block.
        // The player stands at highestY + 1 (feet), highestY + 2 (head).
        int feetY = highestY + 1;

        Block ground = world.getBlockAt(x, highestY, z);
        Block feet   = world.getBlockAt(x, feetY, z);
        Block head   = world.getBlockAt(x, feetY + 1, z);

        // Avoid void / below meaningful terrain
        if (highestY < 60) return null;

        // Avoid spawning near the build height ceiling
        if (feetY + 1 >= world.getMaxHeight() - 5) return null;

        // Ground must be solid and not lava
        if (!ground.getType().isSolid()) return null;
        if (ground.getType() == Material.LAVA) return null;

        // Powder snow is a lethal trap
        if (ground.getType() == Material.POWDER_SNOW) return null;

        // Player must fit (feet and head passable)
        if (!feet.isPassable()) return null;
        if (!head.isPassable()) return null;

        // No lava at feet or head height
        if (config.isAvoidLava()) {
            if (feet.getType() == Material.LAVA) return null;
            if (head.getType() == Material.LAVA) return null;
        }

        // Standing in deep water is annoying but survivable — allow it

        return new Location(world, x + 0.5, feetY, z + 0.5);
    }

    /**
     * A far looser version of {@link #findSafeY} used once the strict search has been exhausted:
     * it wants solid ground and room to stand, and nothing else. Ocean, biome and height-preference
     * rules are all dropped, because being exiled to a beach on an island is a better outcome than
     * being handed back to world spawn.
     *
     * <p>The height map names the topmost block that stops motion, which is frequently <em>not</em>
     * something anybody can stand on: leaves, a snow layer, the surface of a lake. Testing only that
     * block would reject a perfectly ordinary forest and send the caller looking elsewhere, so the
     * column is walked down through the canopy to the ground beneath it.
     *
     * <p>The descent stops at the first liquid rather than continuing to the floor below it. A
     * seabed twenty blocks under an ocean satisfies every other rule here and drowns whoever lands
     * on it; over water this returns null and lets the caller find real ground nearby.
     *
     * @return a standable location, or null if there is genuinely nothing here (void, water, or lava
     *         at the surface)
     */
    public Location findAnyLanding(World world, int x, int z) {
        int highestY = world.getHighestBlockYAt(x, z);
        if (highestY <= world.getMinHeight()) return null;

        int top = Math.min(highestY + 1, world.getMaxHeight() - 3);
        int floor = Math.max(top - CANOPY_DEPTH, world.getMinHeight() + 1);

        for (int feetY = top; feetY >= floor; feetY--) {
            if (world.getBlockAt(x, feetY, z).isLiquid()) return null;
            if (isSafeStanding(world, x, feetY, z)) {
                return new Location(world, x + 0.5, feetY, z + 0.5);
            }
        }
        return null;
    }

    /**
     * Finds the End portal block nearest to a column, searching outward in rings and downward
     * through each column so the most accessible portal in reach answers first.
     *
     * <p>This is how a traveller coming out of the End is put back at the portal they left from
     * rather than on the roof of the stronghold containing it. The reflection names the portal's
     * <em>column</em>; the portal itself is usually far below the surface, and no height map will
     * ever point at it.
     *
     * <p>Columns are read from the bottom up. End portal frames cannot be obtained in survival, so
     * in practice every End portal in the world is a stronghold's, and a stronghold's portal room
     * sits far nearer the bottom of the world than the top — searching upward finds it in a few
     * dozen reads instead of a few hundred.
     *
     * <p>All chunks within {@code radius} must already be loaded.
     *
     * @return the position of a portal block, or null if there is no portal within reach
     */
    public Location findPortalBlock(World world, int centreX, int centreZ, int radius) {
        int bottom = world.getMinHeight();
        int top = world.getMaxHeight() - 1;

        for (int ring = 0; ring <= radius; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    // Only the perimeter of this ring; inner columns were checked already.
                    if (ring > 0 && Math.abs(dx) != ring && Math.abs(dz) != ring) continue;

                    int x = centreX + dx;
                    int z = centreZ + dz;
                    for (int y = bottom; y <= top; y++) {
                        if (world.getBlockAt(x, y, z).getType() == Material.END_PORTAL) {
                            return new Location(world, x, y, z);
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * The last resort: a location at these coordinates no matter what is there. Never returns null,
     * because its only caller has already exhausted every search that is allowed to fail.
     */
    public Location forceLanding(World world, int x, int z) {
        int highestY = world.getHighestBlockYAt(x, z);
        int feetY = Math.max(highestY + 1, world.getSeaLevel() + 1);
        feetY = Math.min(feetY, world.getMaxHeight() - 3);
        feetY = Math.max(feetY, world.getMinHeight() + 1);
        return new Location(world, x + 0.5, feetY, z + 0.5);
    }

    /**
     * True if a player can stand at the given feet position without being hurt, suffocated or
     * immediately teleported again. Unlike {@link #findSafeY} this checks one exact spot rather
     * than searching a column, and it rejects portal blocks — returning a traveller into the
     * portal they just left would send them straight back.
     *
     * <p>The chunk must already be loaded.
     */
    public boolean isSafeStanding(World world, int x, int feetY, int z) {
        if (feetY - 1 < world.getMinHeight()) return false;
        if (feetY + 1 >= world.getMaxHeight() - 1) return false;

        Block ground = world.getBlockAt(x, feetY - 1, z);
        Block feet   = world.getBlockAt(x, feetY, z);
        Block head   = world.getBlockAt(x, feetY + 1, z);

        if (!ground.getType().isSolid()) return false;
        if (isHazard(ground.getType())) return false;

        if (!feet.isPassable() || !head.isPassable()) return false;
        if (isHazard(feet.getType()) || isHazard(head.getType())) return false;
        if (isPortal(feet.getType()) || isPortal(head.getType()) || isPortal(ground.getType())) return false;

        return true;
    }

    /**
     * Searches outward from a point for a spot a player can stand on, checking the exact position
     * first and then successively larger rings. Deterministic ordering: the same obstruction always
     * resolves to the same nearby spot, so a repeatedly used portal stays predictable.
     *
     * <p>All chunks within {@code radius} must already be loaded. Returns null if nothing is safe.
     */
    public Location findSafeStandingNear(World world, int x, int y, int z, int radius) {
        for (int ring = 0; ring <= radius; ring++) {
            for (int dy : verticalOffsets()) {
                int feetY = y + dy;
                for (int dx = -ring; dx <= ring; dx++) {
                    for (int dz = -ring; dz <= ring; dz++) {
                        // Only the perimeter of this ring; inner positions were checked already.
                        if (ring > 0 && Math.abs(dx) != ring && Math.abs(dz) != ring) continue;
                        if (isSafeStanding(world, x + dx, feetY, z + dz)) {
                            return new Location(world, x + dx + 0.5, feetY, z + dz + 0.5);
                        }
                    }
                }
            }
        }
        return null;
    }

    /** Vertical offsets tried at each ring: the recorded level first, then just above, then below. */
    private static int[] verticalOffsets() {
        return new int[]{0, 1, -1, 2, -2, 3, -3, 4, -4};
    }

    /** Lava is always rejected here regardless of avoidLava: this predicate is about surviving arrival. */
    private boolean isHazard(Material material) {
        return material == Material.LAVA
            || material == Material.POWDER_SNOW
            || material == Material.FIRE
            || material == Material.SOUL_FIRE
            || material == Material.MAGMA_BLOCK
            || material == Material.CACTUS;
    }

    private boolean isPortal(Material material) {
        return material == Material.END_PORTAL
            || material == Material.NETHER_PORTAL
            || material == Material.END_GATEWAY;
    }
}
