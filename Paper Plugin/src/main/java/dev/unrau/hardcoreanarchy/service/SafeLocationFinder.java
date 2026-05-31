package dev.unrau.hardcoreanarchy.service;

import dev.unrau.hardcoreanarchy.config.PluginConfig;
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
}
