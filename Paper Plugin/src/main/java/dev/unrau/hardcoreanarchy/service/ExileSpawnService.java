package dev.unrau.hardcoreanarchy.service;

import dev.unrau.hardcoreanarchy.config.PluginConfig;
import dev.unrau.hardcoreanarchy.data.PlayerData;
import dev.unrau.hardcoreanarchy.data.PlayerDataStore;
import dev.unrau.hardcoreanarchy.log.SpawnLogger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Orchestrates async exile location searches. All world access (getHighestBlockYAt,
 * getBlockAt, biome queries) runs after the chunk is confirmed loaded via
 * getChunkAtAsync, so the main thread is never blocked on chunk generation.
 */
public class ExileSpawnService {

    private final JavaPlugin plugin;
    private final PluginConfig config;
    private final SafeLocationFinder finder;
    private final PlayerDataStore dataStore;
    private final SpawnLogger logger;
    private final Random random = new Random();

    public ExileSpawnService(JavaPlugin plugin, PluginConfig config,
                             SafeLocationFinder finder, PlayerDataStore dataStore,
                             SpawnLogger logger) {
        this.plugin = plugin;
        this.config = config;
        this.finder = finder;
        this.dataStore = dataStore;
        this.logger = logger;
    }

    // -------------------------------------------------------------------------
    // First join
    // -------------------------------------------------------------------------

    /**
     * Async: find a spawn for a first-time player. Calls back on the main thread.
     */
    public void findFirstJoinSpawn(World world, UUID uuid, String playerName,
                                   Consumer<Location> onFound) {
        int min = config.getFirstJoinMinDistance();
        int max = config.getFirstJoinMaxDistance();
        searchAsync(world, null, min, max, 0, onFound, uuid, playerName);
    }

    // -------------------------------------------------------------------------
    // Death / exile respawn
    // -------------------------------------------------------------------------

    /**
     * Begins an async exile location search triggered by a death event.
     * When the location is found it is persisted and the player is teleported
     * (either via the pending respawn slot or a delayed teleport if they already
     * clicked through the respawn screen).
     */
    public void beginExileCalculation(Player player, Location deathLoc) {
        UUID uuid = player.getUniqueId();
        String playerName = player.getName();
        World world = Bukkit.getWorld(config.getWorldName());
        if (world == null) {
            plugin.getLogger().severe("World '" + config.getWorldName() + "' not found — cannot calculate exile spawn.");
            return;
        }

        int minFromZero  = config.getDeathRespawnMinDistanceFromZero();
        int maxFromZero  = config.getDeathRespawnMaxDistanceFromZero();

        searchAsync(world, deathLoc, minFromZero, maxFromZero, 0, location -> {
            // Back on main thread
            PlayerData data = dataStore.load(uuid);
            data.setCalculatingRespawn(false);
            data.setPendingRespawn(location.getWorld().getName(), location.getX(), location.getY(), location.getZ());
            data.setLastExile(location.getWorld().getName(), location.getX(), location.getY(), location.getZ());
            boolean needsDelay = data.isNeedsDelayedTeleport();
            data.setNeedsDelayedTeleport(false);
            dataStore.save(uuid, data);

            logger.log(SpawnLogger.Reason.EXILE_RESPAWN, uuid, playerName,
                location.getWorld().getName(), location.getX(), location.getY(), location.getZ());

            if (needsDelay) {
                // Player already clicked Respawn and is alive — teleport them now
                Player live = Bukkit.getPlayer(uuid);
                if (live != null && live.isOnline()) {
                    live.teleport(location);
                    live.sendMessage(config.getMsgDeathScattered());
                }
            }
        }, uuid, playerName);
    }

    // -------------------------------------------------------------------------
    // Core async search loop
    // -------------------------------------------------------------------------

    /**
     * Attempts up to maxSafeSpawnAttempts candidate locations. Each attempt loads
     * the target chunk asynchronously, then evaluates safety on the main thread
     * callback. On exhaustion, falls back to the last ring-valid candidate (ignoring
     * the death-distance constraint) rather than returning null.
     */
    private void searchAsync(World world, Location deathLoc,
                             int minFromZero, int maxFromZero,
                             int attempt, Consumer<Location> onFound,
                             UUID uuid, String playerName) {
        if (attempt >= config.getMaxSafeSpawnAttempts()) {
            // Exhausted attempts — try one more time ignoring death-distance constraint
            plugin.getLogger().warning("[HardcoreAnarchy] Exhausted " + attempt
                + " spawn attempts for " + playerName + "; retrying without death-distance filter.");
            searchAsyncRelaxed(world, minFromZero, maxFromZero, 0, onFound, uuid, playerName);
            return;
        }

        int[] xz = randomXZ(minFromZero, maxFromZero);
        int x = xz[0], z = xz[1];

        // Death-distance filter (fast, no chunk load needed)
        if (deathLoc != null) {
            double dx = x - deathLoc.getX();
            double dz = z - deathLoc.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist < config.getDeathRespawnMinDistanceFromDeath()) {
                // Skip this candidate without loading the chunk
                searchAsync(world, deathLoc, minFromZero, maxFromZero, attempt + 1, onFound, uuid, playerName);
                return;
            }
        }

        world.getChunkAtAsync(x >> 4, z >> 4).thenAccept(chunk -> {
            // This callback may run on a non-main thread; schedule safety check on main thread
            Bukkit.getScheduler().runTask(plugin, () -> {
                Location candidate = finder.findSafeY(world, x, z);
                if (candidate != null) {
                    onFound.accept(candidate);
                } else {
                    searchAsync(world, deathLoc, minFromZero, maxFromZero, attempt + 1, onFound, uuid, playerName);
                }
            });
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.WARNING, "Chunk load failed at " + x + "," + z, ex);
            Bukkit.getScheduler().runTask(plugin, () ->
                searchAsync(world, deathLoc, minFromZero, maxFromZero, attempt + 1, onFound, uuid, playerName));
            return null;
        });
    }

    /** Fallback search that ignores the death-distance constraint. */
    private void searchAsyncRelaxed(World world, int minFromZero, int maxFromZero,
                                    int attempt, Consumer<Location> onFound,
                                    UUID uuid, String playerName) {
        if (attempt >= config.getMaxSafeSpawnAttempts()) {
            plugin.getLogger().severe("[HardcoreAnarchy] Could not find any safe spawn for " + playerName
                + " after " + (config.getMaxSafeSpawnAttempts() * 2) + " attempts. Using world spawn as emergency fallback.");
            World w = Bukkit.getWorld(config.getWorldName());
            onFound.accept(w != null ? w.getSpawnLocation() : world.getSpawnLocation());
            return;
        }

        int[] xz = randomXZ(minFromZero, maxFromZero);
        int x = xz[0], z = xz[1];

        world.getChunkAtAsync(x >> 4, z >> 4).thenAccept(chunk ->
            Bukkit.getScheduler().runTask(plugin, () -> {
                Location candidate = finder.findSafeY(world, x, z);
                if (candidate != null) {
                    onFound.accept(candidate);
                } else {
                    searchAsyncRelaxed(world, minFromZero, maxFromZero, attempt + 1, onFound, uuid, playerName);
                }
            })
        ).exceptionally(ex -> {
            Bukkit.getScheduler().runTask(plugin, () ->
                searchAsyncRelaxed(world, minFromZero, maxFromZero, attempt + 1, onFound, uuid, playerName));
            return null;
        });
    }

    // -------------------------------------------------------------------------
    // Coordinate generation
    // -------------------------------------------------------------------------

    /**
     * Returns a random (x, z) pair uniformly distributed in the annulus between
     * minDist and maxDist from the origin. Uses sqrt of a uniform sample to ensure
     * area-uniform distribution rather than clustering near the inner edge.
     */
    private int[] randomXZ(int minDist, int maxDist) {
        double angle = random.nextDouble() * 2 * Math.PI;
        // Uniform distribution over annular area: r = sqrt(U * (max² - min²) + min²)
        double minSq = (double) minDist * minDist;
        double maxSq = (double) maxDist * maxDist;
        double r = Math.sqrt(random.nextDouble() * (maxSq - minSq) + minSq);
        int x = (int) Math.round(r * Math.cos(angle));
        int z = (int) Math.round(r * Math.sin(angle));
        return new int[]{x, z};
    }
}
