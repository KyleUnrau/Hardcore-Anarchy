package dev.unrau.samsara.service;

import dev.unrau.samsara.config.PluginConfig;
import dev.unrau.samsara.data.PlayerData;
import dev.unrau.samsara.data.PlayerDataStore;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Works out where a player will wake up before they are let into the world at all.
 *
 * <p>Placing an exile after the join is too late, and visibly so. A player joining for the first time
 * is put at the world spawn by the server, the chunks around it are sent to them, their client
 * renders 0,0 — and only then, once the search comes back, are they moved a hundred thousand blocks
 * away. Most of the time the search is quick enough that nobody notices. Sometimes it is not, and
 * then the first thing a new player sees on a server whose whole premise is that nobody shares a
 * spawn is everybody's spawn.
 *
 * <p>That flash is not only untidy. Every player who has ever joined has been sent the same chunks
 * around 0,0, has stood in them for a moment, and has had whatever is there loaded and ticking on
 * their behalf. The world spawn is a place on this map like any other, and the plugin's own rule is
 * that nobody is ever placed there.
 *
 * <p>So the work moves in front of the join. Minecraft asks the server twice where a player goes: the
 * login handshake happens on its own thread, off the main thread, before the player exists — which is
 * a thread that can afford to wait — and the spawn position is asked for once more while that same
 * connection is being configured, before the player is built and before a single chunk is sent. This
 * class does the search and the chunk loading during the first, and has an answer ready for the
 * second. The player's client shows its ordinary "Logging in" screen for however long that takes,
 * and the first terrain they are ever sent is the terrain they are going to be standing on.
 *
 * <p>Nothing here is load-bearing. Every failure — no world, a search that will not resolve, a
 * timeout, a shutdown mid-login — simply prepares nothing, and the join falls back to the old
 * behaviour of placing the player afterwards. A slow search must never be the reason somebody cannot
 * log in.
 */
public final class ArrivalPreparation {

    /** Why a player is being put somewhere, which decides what the join then writes down. */
    public enum Kind {
        /** A life that has not begun yet: this is the exile that begins it. */
        FIRST_JOIN,
        /** An exile that was calculated for a death the player never finished respawning from. */
        PENDING_EXILE
    }

    /** Where a player is going, and why. */
    public record Placement(Location location, Kind kind) {}

    /**
     * How long a prepared placement is kept for a player who never arrives.
     *
     * <p>A login can fail after the handshake — a whitelist, a ban, a dropped connection — and
     * nothing tells this class about it. Generous, because the only cost of holding one is a
     * location, and the real bound is that a placement is consumed the moment its player joins.
     */
    private static final long ABANDONED_AFTER_MILLIS = 120_000L;

    private final JavaPlugin plugin;
    private final PluginConfig config;
    private final PlayerDataStore dataStore;
    private final ExileSpawnService spawnService;

    /** Who is on their way in, and where each of them is going. */
    private final ArrivalQueue queue = new ArrivalQueue(ABANDONED_AFTER_MILLIS);

    public ArrivalPreparation(JavaPlugin plugin, PluginConfig config, PlayerDataStore dataStore,
                              ExileSpawnService spawnService) {
        this.plugin = plugin;
        this.config = config;
        this.dataStore = dataStore;
        this.spawnService = spawnService;
    }

    /**
     * Finds this player's place in the world and loads the ground under it, on the login thread that
     * is already holding their connection open.
     *
     * <p>Blocking is the entire point: the login is what waits, rather than the player's own client
     * after it has already rendered somewhere else.
     */
    public void prepare(UUID uuid, String playerName) {
        if (!config.isArrivalPrepareBeforeJoin()) return;

        World world = spawnService.resolveOverworld();
        if (world == null) return;

        long deadline = System.nanoTime()
            + TimeUnit.SECONDS.toNanos(config.getArrivalPreparationTimeoutSeconds());

        try {
            Placement placement = choose(world, dataStore.load(uuid), uuid, playerName, deadline);
            if (placement == null) return;

            // The search only guarantees the one chunk it judged. Widen that to the ground a player
            // can see themselves standing on, so the first frame they are shown is a finished one.
            Location at = placement.location();
            await(ChunkArea.load(at.getWorld(), at.getBlockX(), at.getBlockZ(),
                config.getArrivalPreloadRadius()), deadline);

            queue.prepared(uuid, placement);
        } catch (TimeoutException e) {
            // The search is not cancelled — it is a chain of main-thread tasks and cannot be. It
            // finishes into a future nobody is holding, which costs nothing, and the join searches
            // again the old way.
            plugin.getLogger().warning("[Samsara] Could not settle where " + playerName
                + " belongs within " + config.getArrivalPreparationTimeoutSeconds()
                + " seconds; letting them in and placing them once they are here.");
        } catch (ExecutionException | RuntimeException e) {
            plugin.getLogger().warning("[Samsara] Preparing an arrival for " + playerName
                + " failed (" + e + "); letting them in and placing them once they are here.");
        } catch (InterruptedException e) {
            // The login was abandoned. Nothing to prepare for and nothing to report.
            Thread.currentThread().interrupt();
        }
    }

    /**
     * The position to put this player at, claimed by the spawn-position event.
     *
     * <p>Moves the placement rather than dropping it: the join that follows still has to write down
     * what this arrival means, and by then the position alone no longer says.
     *
     * @return where they go, or null if nothing was prepared for them
     */
    public Location claim(UUID uuid) {
        Placement placement = queue.claim(uuid);
        return placement == null ? null : placement.location().clone();
    }

    /**
     * The placement this player was let in on, claimed by the join that records it.
     *
     * @return what was prepared, or null if they were placed the ordinary way
     */
    public Placement take(UUID uuid) {
        return queue.take(uuid);
    }

    /** Drops anything held for a player, whether they arrived or not. */
    public void discard(UUID uuid) {
        queue.discard(uuid);
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    /**
     * What this player needs, if anything.
     *
     * <p>Two arrivals are worth preparing, and they are the two the server would otherwise answer
     * with the world spawn: a life that has not begun, and one whose exile was worked out for a death
     * the player then logged out of. Everybody else is already somewhere, and the server puts them
     * back exactly where they left.
     */
    private Placement choose(World world, PlayerData data, UUID uuid, String playerName, long deadline)
        throws InterruptedException, ExecutionException, TimeoutException {

        if (!data.isHasJoinedBefore() || data.isStaleFor(world.getUID())) {
            Location exile = await(spawnService.findFreshSpawnAsync(world, uuid, playerName), deadline);
            return exile == null ? null : new Placement(exile, Kind.FIRST_JOIN);
        }

        if (data.isNeedsDelayedTeleport() && data.isHasPendingRespawn()) {
            World pending = Bukkit.getWorld(data.getPendingRespawnWorld());
            if (pending == null) return null;
            return new Placement(new Location(pending, data.getPendingRespawnX(),
                data.getPendingRespawnY(), data.getPendingRespawnZ()), Kind.PENDING_EXILE);
        }

        return null;
    }

    private <T> T await(CompletableFuture<T> future, long deadline)
        throws InterruptedException, ExecutionException, TimeoutException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new TimeoutException("out of time");
        return future.get(remaining, TimeUnit.NANOSECONDS);
    }
}
