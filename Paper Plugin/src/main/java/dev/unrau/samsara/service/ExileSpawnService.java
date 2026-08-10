package dev.unrau.samsara.service;

import dev.unrau.samsara.config.PluginConfig;
import dev.unrau.samsara.data.PlayerData;
import dev.unrau.samsara.data.PlayerDataStore;
import dev.unrau.samsara.data.JournalEntry;
import dev.unrau.samsara.log.PlayerJournal;
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
 *
 * <p>First join and death respawn draw from the same band of coordinates: a new life is a new life
 * however it began.
 *
 * <p>The search degrades rather than gives up. If the terrain filters cannot be satisfied it drops
 * the distance-from-death rule, then the terrain filters themselves, before it will ever consider
 * world spawn — landing at world spawn is the one outcome this plugin exists to prevent.
 */
public class ExileSpawnService {

    /** Ordered stages of the search; each one relaxes a constraint the previous stage enforced. */
    private enum Phase {
        /** Terrain filters plus the minimum distance from the death location. */
        STRICT,
        /** Terrain filters only. */
        RELAXED,
        /** Any solid surface at all, ocean and biome filters ignored. */
        FORCED
    }

    private final JavaPlugin plugin;
    private final PluginConfig config;
    private final SafeLocationFinder finder;
    private final PlayerDataStore dataStore;
    private final PlayerJournal journal;
    private final Random random = new Random();

    public ExileSpawnService(JavaPlugin plugin, PluginConfig config,
                             SafeLocationFinder finder, PlayerDataStore dataStore,
                             PlayerJournal journal) {
        this.plugin = plugin;
        this.config = config;
        this.finder = finder;
        this.dataStore = dataStore;
        this.journal = journal;
    }

    // -------------------------------------------------------------------------
    // World resolution
    // -------------------------------------------------------------------------

    /**
     * The Overworld exiles are placed in. Falls back to the first normal-environment world, then to
     * any loaded world, when {@code worldName} names a world that isn't loaded — a wrong world name
     * in config.yml should not be able to strand a player.
     *
     * @return a usable world, or null only if the server has no worlds loaded at all
     */
    public World resolveOverworld() {
        World configured = Bukkit.getWorld(config.getWorldName());
        if (configured != null) return configured;

        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() == World.Environment.NORMAL) {
                plugin.getLogger().warning("[Samsara] World '" + config.getWorldName()
                    + "' from config.yml is not loaded; using '" + world.getName() + "' for exile spawns.");
                return world;
            }
        }

        if (!Bukkit.getWorlds().isEmpty()) {
            World fallback = Bukkit.getWorlds().get(0);
            plugin.getLogger().severe("[Samsara] No Overworld found; falling back to '"
                + fallback.getName() + "' for exile spawns.");
            return fallback;
        }

        plugin.getLogger().severe("[Samsara] No worlds are loaded; cannot choose an exile spawn.");
        return null;
    }

    // -------------------------------------------------------------------------
    // First join
    // -------------------------------------------------------------------------

    /**
     * Async: find a spawn for a life that has no death behind it — a first join, or a player whose
     * stored data turned out to be unusable. Calls back on the main thread.
     *
     * <p>Uses the same band as a death respawn; only the distance-from-death rule is absent,
     * because there is no death to measure from.
     */
    public void findFreshSpawn(World world, UUID uuid, String playerName,
                               Consumer<Location> onFound) {
        search(world, areaFor(world), null, Phase.STRICT, 0, onFound, uuid, playerName);
    }

    /**
     * As {@link #findFreshSpawn}, for a caller that is not on the main thread and has something to
     * wait on — the login thread, which holds a player's connection open until their exile is known.
     *
     * <p>The search itself is started on the main thread, because choosing the band reads the world
     * border, and completes there too. Only the waiting happens elsewhere.
     *
     * @return the exile, or a future completed exceptionally if the search could not even be started
     */
    public CompletableFuture<Location> findFreshSpawnAsync(World world, UUID uuid, String playerName) {
        CompletableFuture<Location> found = new CompletableFuture<>();
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    search(world, areaFor(world), null, Phase.STRICT, 0, found::complete, uuid, playerName);
                } catch (RuntimeException e) {
                    found.completeExceptionally(e);
                }
            });
        } catch (IllegalStateException e) {
            // The scheduler refuses work once the plugin is disabling. Nothing is pending, so the
            // caller can fall back cleanly rather than waiting for a task that will never run.
            found.completeExceptionally(e);
        }
        return found;
    }

    // -------------------------------------------------------------------------
    // Death / exile respawn
    // -------------------------------------------------------------------------

    /**
     * Begins an async exile location search triggered by a death event, measuring the
     * minimum-distance rule from the death location itself.
     */
    public void beginExileCalculation(Player player, Location deathLoc) {
        beginExileCalculation(player, deathLoc, deathLoc);
    }

    /**
     * Begins an async exile location search triggered by a death event.
     * When the location is found it is persisted and the player is teleported
     * (either via the pending respawn slot or a delayed teleport if they already
     * clicked through the respawn screen).
     *
     * @param distanceAnchor the Overworld point the new life must be far from. Usually the death
     *                       location; for a death in another dimension the caller supplies the
     *                       Overworld position that death belongs to, since coordinates in the End
     *                       or Nether are not comparable distances in the Overworld. May be null,
     *                       in which case only the distance-from-zero rule applies.
     */
    public void beginExileCalculation(Player player, Location deathLoc, Location distanceAnchor) {
        UUID uuid = player.getUniqueId();
        String playerName = player.getName();
        World world = resolveOverworld();
        if (world == null) {
            // Nothing can be done without a world; leave the flags set so the next join retries.
            return;
        }

        // An anchor from another dimension is not a comparable Overworld distance — ignore it
        // rather than filtering candidates against a meaningless number.
        Location anchor = (distanceAnchor != null && distanceAnchor.getWorld() != null
            && distanceAnchor.getWorld().getEnvironment() == World.Environment.NORMAL)
            ? distanceAnchor : null;

        search(world, areaFor(world), anchor, Phase.STRICT, 0, location -> {
            // Back on main thread
            PlayerData data = dataStore.load(uuid);
            data.setCalculatingRespawn(false);
            data.setPendingRespawn(location.getWorld().getName(), location.getX(), location.getY(), location.getZ());
            data.setLastExile(location.getWorld().getName(), location.getX(), location.getY(), location.getZ());
            data.setWorldUid(location.getWorld().getUID());
            boolean needsDelay = data.isNeedsDelayedTeleport();
            data.setNeedsDelayedTeleport(false);
            dataStore.save(uuid, data);

            journal.record(JournalEntry.Reason.EXILE_RESPAWN, uuid, playerName,
                location.getWorld().getName(), location.getX(), location.getY(), location.getZ());

            if (needsDelay) {
                // Player already clicked Respawn and is alive — teleport them now
                Player live = Bukkit.getPlayer(uuid);
                if (live != null && live.isOnline()) {
                    live.teleport(location);
                }
            }
        }, uuid, playerName);
    }

    // -------------------------------------------------------------------------
    // Spawn area
    // -------------------------------------------------------------------------

    /**
     * Resolves the configured distances against this world's border. Recomputed per search so a
     * {@code /worldborder} change or a {@code /samsara reload} takes effect immediately.
     */
    public SpawnArea areaFor(World world) {
        var border = world.getWorldBorder();
        return SpawnArea.resolve(
            config.getSpawnAreaShape(),
            config.getSpawnMinDistanceFromZero(),
            config.getSpawnMaxDistanceFromZero(),
            border.getCenter().getX(),
            border.getCenter().getZ(),
            border.getSize(),
            config.isRespectWorldBorder(),
            plugin.getLogger()
        );
    }

    // -------------------------------------------------------------------------
    // Core async search loop
    // -------------------------------------------------------------------------

    /**
     * Attempts up to maxSafeSpawnAttempts candidate locations per phase, moving to the next phase on
     * exhaustion. Each attempt loads the target chunk asynchronously, then evaluates the candidate
     * on the main thread callback.
     */
    private void search(World world, SpawnArea area, Location deathAnchor, Phase phase,
                        int attempt, Consumer<Location> onFound,
                        UUID uuid, String playerName) {
        int maxAttempts = config.getMaxSafeSpawnAttempts();

        // The death-distance filter needs no chunk, so rejections are retried in this loop rather
        // than by recursing — a large maxSafeSpawnAttempts would otherwise pile up stack frames.
        int x = 0;
        int z = 0;
        boolean picked = false;
        while (attempt < maxAttempts && !picked) {
            int[] xz = area.randomPoint(random);
            x = xz[0];
            z = xz[1];

            if (phase == Phase.STRICT && deathAnchor != null
                && Math.hypot(x - deathAnchor.getX(), z - deathAnchor.getZ())
                   < config.getDeathRespawnMinDistanceFromDeath()) {
                attempt++;
                continue;
            }
            picked = true;
        }

        if (!picked) {
            advancePhase(world, area, deathAnchor, phase, onFound, uuid, playerName);
            return;
        }

        final int candidateX = x;
        final int candidateZ = z;
        final int nextAttempt = attempt + 1;

        world.getChunkAtAsync(x >> 4, z >> 4).thenAccept(chunk ->
            // This callback may run on a non-main thread; schedule the world reads on the main thread
            Bukkit.getScheduler().runTask(plugin, () -> {
                Location candidate = phase == Phase.FORCED
                    ? finder.findAnyLanding(world, candidateX, candidateZ)
                    : finder.findSafeY(world, candidateX, candidateZ);
                if (candidate != null) {
                    onFound.accept(candidate);
                } else {
                    search(world, area, deathAnchor, phase, nextAttempt, onFound, uuid, playerName);
                }
            })
        ).exceptionally(ex -> {
            plugin.getLogger().log(Level.WARNING, "Chunk load failed at " + candidateX + "," + candidateZ, ex);
            Bukkit.getScheduler().runTask(plugin, () ->
                search(world, area, deathAnchor, phase, nextAttempt, onFound, uuid, playerName));
            return null;
        });
    }

    /** Drops one constraint and restarts the attempt count, or gives up if there is nothing left to drop. */
    private void advancePhase(World world, SpawnArea area, Location deathAnchor, Phase phase,
                              Consumer<Location> onFound, UUID uuid, String playerName) {
        int attempts = config.getMaxSafeSpawnAttempts();
        switch (phase) {
            case STRICT -> {
                plugin.getLogger().warning("[Samsara] Exhausted " + attempts + " spawn attempts for "
                    + playerName + " in " + area + "; retrying without the distance-from-death filter.");
                search(world, area, null, Phase.RELAXED, 0, onFound, uuid, playerName);
            }
            case RELAXED -> {
                plugin.getLogger().warning("[Samsara] Still no safe spawn for " + playerName
                    + " after " + (attempts * 2) + " attempts; accepting any solid ground, ocean included.");
                search(world, area, null, Phase.FORCED, 0, onFound, uuid, playerName);
            }
            case FORCED -> {
                // Candidates in this phase only need solid ground, so reaching here means the area
                // itself is unusable. Take one point on faith rather than sending anyone to spawn:
                // a rough landing at a random coordinate is still an exile.
                int[] xz = area.randomPoint(random);
                plugin.getLogger().severe("[Samsara] Could not find any ground for " + playerName
                    + " after " + (attempts * 3) + " attempts in " + area
                    + "; placing them at " + xz[0] + "," + xz[1] + " regardless of what is there.");
                world.getChunkAtAsync(xz[0] >> 4, xz[1] >> 4).thenAccept(chunk ->
                    Bukkit.getScheduler().runTask(plugin, () ->
                        onFound.accept(finder.forceLanding(world, xz[0], xz[1])))
                ).exceptionally(ex -> {
                    plugin.getLogger().log(Level.SEVERE, "[Samsara] Final fallback chunk load failed for "
                        + playerName + "; using world spawn.", ex);
                    Bukkit.getScheduler().runTask(plugin, () -> onFound.accept(world.getSpawnLocation()));
                    return null;
                });
            }
        }
    }
}
