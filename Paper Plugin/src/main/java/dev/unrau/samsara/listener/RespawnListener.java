package dev.unrau.samsara.listener;

import dev.unrau.samsara.data.PlayerData;
import dev.unrau.samsara.data.PlayerDataStore;
import dev.unrau.samsara.service.DimensionalTravelService;
import dev.unrau.samsara.service.ExileSpawnService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;

public class RespawnListener implements Listener {

    private final JavaPlugin plugin;
    private final PlayerDataStore dataStore;
    private final ExileSpawnService spawnService;
    private final DimensionalTravelService travelService;

    public RespawnListener(JavaPlugin plugin, PlayerDataStore dataStore,
                           ExileSpawnService spawnService, DimensionalTravelService travelService) {
        this.plugin = plugin;
        this.dataStore = dataStore;
        this.spawnService = spawnService;
        this.travelService = travelService;
    }

    // HIGHEST so we override any other plugin that might set respawn location
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();

        // Vanilla leaves the End through respawn machinery rather than through a portal event: the
        // exit portal literally respawns the player. That is the single most dangerous overlap in
        // this plugin — untouched, using it would look exactly like dying, and would exile a player
        // who never died. It is handled here and never falls through to the exile path.
        if (event.getRespawnReason() == PlayerRespawnEvent.RespawnReason.END_PORTAL) {
            handleExitPortal(event, player);
            return;
        }

        PlayerData data = dataStore.load(player.getUniqueId());

        if (!data.isHasJoinedBefore()) {
            // Edge case: first-join logic handles initial spawn; nothing to do here
            return;
        }

        // Set when this handler, rather than DeathListener, has to start the search.
        boolean startSearchHere = false;

        if (data.isHasPendingRespawn()) {
            World world = Bukkit.getWorld(data.getPendingRespawnWorld());
            if (world != null) {
                // Exile location is ready — use it
                Location exile = new Location(world,
                    data.getPendingRespawnX(), data.getPendingRespawnY(), data.getPendingRespawnZ());
                event.setRespawnLocation(exile);
                data.clearPendingRespawn();
                data.setCalculatingRespawn(false);
                dataStore.save(player.getUniqueId(), data);
                return;
            }

            // The recorded world is gone, so the record is unusable. Recalculate rather than
            // letting vanilla decide.
            plugin.getLogger().warning("[Samsara] Pending respawn world '"
                + data.getPendingRespawnWorld() + "' for " + player.getName()
                + " is not loaded; calculating a new exile.");
            data.clearPendingRespawn();
            startSearchHere = true;
        }

        if (!data.isCalculatingRespawn() && !startSearchHere) {
            // No search running and no exile waiting. Vanilla would hand them their bed or world
            // spawn, which is the one outcome this plugin exists to prevent. Reached when the plugin
            // was disabled during the death, or when the data file was lost between dying and
            // respawning.
            plugin.getLogger().warning("[Samsara] " + player.getName()
                + " respawned with no exile recorded; calculating one now rather than using their spawn point.");
            startSearchHere = true;
        }

        // A search is running or is about to start, and the respawn screen will not wait for it. The
        // player has to be put somewhere for the second or so it takes, and then teleported when it
        // finishes (handled in ExileSpawnService).
        //
        // Not the world spawn. Every player on the server would pass through the same coordinates,
        // be sent the same chunks and tick the same ground on the way to their own private life, and
        // a hiccup that lost the teleport would leave somebody standing there — which is the one
        // outcome this plugin exists to prevent. Where this life began is a real place, it is theirs
        // alone, and it was checked for safety when they were put there. It is a waiting room, not a
        // destination: the exile that is being searched for is still where they are going.
        Location waiting = waitingRoom(player, data);
        if (waiting != null) {
            event.setRespawnLocation(waiting);
        }
        data.setCalculatingRespawn(true);
        data.setNeedsDelayedTeleport(true);
        dataStore.save(player.getUniqueId(), data);

        if (startSearchHere) {
            Location anchor = deathAnchor(data);
            // Next tick, once the player is alive and at their temporary respawn point.
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player live = Bukkit.getPlayer(player.getUniqueId());
                if (live != null && live.isOnline()) {
                    spawnService.beginExileCalculation(live, live.getLocation(), anchor);
                }
            });
        }
    }

    /**
     * Handles the End exit portal at End 0,0, which vanilla delivers as a respawn rather than as a
     * portal event — the player is literally respawned without having died.
     *
     * <p>Vanilla's own answer is kept, and it is the right one: you wake in your bed, or at your
     * respawn anchor. This is the one place on the server where a bed does what a bed does
     * everywhere else, and it costs nothing, because reaching End 0,0 and killing a dragon is not a
     * shortcut anybody stumbles into.
     *
     * <p>Only the last case needs changing. Vanilla falls back to the world spawn, and there is no
     * such place here — every life begins somewhere of its own. So a player with no bed and no
     * anchor is returned to where <em>this life</em> started: their last exile, or their first
     * spawn if they have never died.
     *
     * <p>No exile is calculated, no life is rotated, no death is recorded. Nobody died.
     */
    private void handleExitPortal(PlayerRespawnEvent event, Player player) {
        if (!travelService.isEnabled()) {
            // Vanilla End travel is in force; let the exit portal behave as it always has.
            return;
        }

        if (event.isBedSpawn() || event.isAnchorSpawn()) {
            travelService.debug(player.getName() + " left the End by the exit portal"
                + " and woke at their " + (event.isAnchorSpawn() ? "respawn anchor" : "bed") + ".");
            return;
        }

        Location personalSpawn = personalSpawn(dataStore.load(player.getUniqueId()));
        if (personalSpawn == null) {
            // No bed, no anchor, and no record of where this life began — which means either a
            // legacy data file or a player the exile system has never placed. Vanilla's world spawn
            // is a poor answer but it is a real, loaded location, and this is not worth an exile.
            plugin.getLogger().warning("[Samsara] " + player.getName() + " used the End exit"
                + " portal with no bed, anchor or recorded spawn; leaving them at the vanilla"
                + " respawn location.");
            return;
        }

        event.setRespawnLocation(personalSpawn);
        travelService.debug(player.getName() + " left the End by the exit portal with"
            + " no bed or anchor; returning them to where this life began, "
            + personalSpawn.getWorld().getName() + " " + (long) personalSpawn.getX() + ","
            + (long) personalSpawn.getZ() + ".");
    }

    /**
     * Somewhere to stand while an exile is being searched for.
     *
     * <p>Where the life that just ended began, when that is known, because it is a real position
     * belonging to this player alone. The world spawn is the last resort and nothing more: a player
     * with no history at all still has to respawn somewhere, and a shared coordinate for a moment is
     * better than a respawn event that refuses to answer.
     */
    private Location waitingRoom(Player player, PlayerData data) {
        Location began = personalSpawn(data);
        if (began != null) return began;

        World world = spawnService.resolveOverworld();
        if (world == null) return null;

        plugin.getLogger().warning("[Samsara] No record of where any of " + player.getName()
            + "'s lives began; they wait at the world spawn while an exile is found for them.");
        return world.getSpawnLocation();
    }

    /**
     * Where this life began: the exile that started it, or the first spawn for a player who has
     * never died. Null when neither is recorded or its world is gone.
     */
    private Location personalSpawn(PlayerData data) {
        Location exile = resolve(data.getLastExileWorld(),
            data.getLastExileX(), data.getLastExileY(), data.getLastExileZ());
        if (exile != null) return exile;

        return resolve(data.getFirstSpawnWorld(),
            data.getFirstSpawnX(), data.getFirstSpawnY(), data.getFirstSpawnZ());
    }

    private Location resolve(String worldName, double x, double y, double z) {
        if (worldName == null) return null;
        World world = Bukkit.getWorld(worldName);
        return world == null ? null : new Location(world, x, y, z);
    }

    /** The recorded death position, when there is a usable one, so a recovery exile is still far from it. */
    private Location deathAnchor(PlayerData data) {
        if (data.getLastDeathWorld() == null) return null;
        World world = Bukkit.getWorld(data.getLastDeathWorld());
        if (world == null || world.getEnvironment() != World.Environment.NORMAL) return null;
        return new Location(world, data.getLastDeathX(), data.getLastDeathY(), data.getLastDeathZ());
    }
}
