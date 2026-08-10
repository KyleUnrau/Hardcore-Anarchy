package dev.unrau.samsara.listener;

import dev.unrau.samsara.data.JournalEntry;
import dev.unrau.samsara.data.PlayerData;
import dev.unrau.samsara.data.PlayerDataStore;
import dev.unrau.samsara.log.PlayerJournal;
import dev.unrau.samsara.service.ArrivalPreparation;
import dev.unrau.samsara.service.ExileSpawnService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Makes sure every player who joins is somewhere this plugin chose.
 *
 * <p>Where they are put is settled before the join wherever possible — see {@link ArrivalPreparation},
 * which does the searching during the login handshake so that the first chunks a player is ever sent
 * are the ones they will be standing in. What is left here is the recording of what that meant, and
 * the fallback for every arrival that could not be prepared in time: the search runs after the join
 * instead, and the player is teleported when it comes back.
 */
public class FirstJoinListener implements Listener {

    private final JavaPlugin plugin;
    private final PlayerDataStore dataStore;
    private final ExileSpawnService spawnService;
    private final PlayerJournal journal;
    private final ArrivalPreparation arrival;

    public FirstJoinListener(JavaPlugin plugin, PlayerDataStore dataStore,
                             ExileSpawnService spawnService, PlayerJournal journal,
                             ArrivalPreparation arrival) {
        this.plugin = plugin;
        this.dataStore = dataStore;
        this.spawnService = spawnService;
        this.journal = journal;
        this.arrival = arrival;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerData data = dataStore.load(player.getUniqueId());

        World world = spawnService.resolveOverworld();
        if (world == null) {
            plugin.getLogger().severe("No world available; cannot place " + player.getName() + ".");
            return;
        }

        // Data left behind by a previous world — or a record that claims a join but holds no
        // position — would otherwise leave this player standing at world spawn. Start them over.
        if (data.isStaleFor(world.getUID())) {
            plugin.getLogger().warning("[Samsara] Player data for " + player.getName()
                + " does not describe a position in '" + world.getName()
                + "'; treating this as a first join and exiling them.");
            data.resetForNewWorld(world.getUID());
            dataStore.save(player.getUniqueId(), data);
        }

        // Settled during the login handshake and already applied — the player is standing on it. All
        // that is left is to say, in their record, what it was.
        ArrivalPreparation.Placement prepared = arrival.take(player.getUniqueId());
        if (prepared != null) {
            record(player, data, prepared);
            return;
        }

        if (data.isHasJoinedBefore()) {
            // Stamp the world id onto records written before the field existed, so the next stale
            // check has something to compare against.
            if (data.getWorldUid() == null) {
                data.setWorldUid(world.getUID());
                dataStore.save(player.getUniqueId(), data);
            }

            // Check if they need a delayed teleport from a previous session's death
            // (e.g. server restarted mid-calculation)
            if (data.isNeedsDelayedTeleport() && data.isHasPendingRespawn()) {
                World pendingWorld = Bukkit.getWorld(data.getPendingRespawnWorld());
                if (pendingWorld != null) {
                    Location dest = new Location(pendingWorld,
                        data.getPendingRespawnX(), data.getPendingRespawnY(), data.getPendingRespawnZ());
                    player.teleport(dest);
                    data.clearPendingRespawn();
                    data.setNeedsDelayedTeleport(false);
                    dataStore.save(player.getUniqueId(), data);
                } else {
                    // The recorded destination world is gone. Rather than leave them wherever the
                    // server put them, find a fresh exile now.
                    plugin.getLogger().warning("[Samsara] Pending respawn world '"
                        + data.getPendingRespawnWorld() + "' for " + player.getName()
                        + " is not loaded; calculating a new exile instead.");
                    data.clearPendingRespawn();
                    data.setCalculatingRespawn(true);
                    dataStore.save(player.getUniqueId(), data);
                    spawnService.beginExileCalculation(player, player.getLocation(), null);
                }
            }
            return;
        }

        // First time joining — teleport to exile spawn
        String playerName = player.getName();
        spawnService.findFreshSpawn(world, player.getUniqueId(), playerName, location -> {
            // Callback fires on main thread. Reload rather than reusing the captured copy: a death
            // during the search would have written to the same file.
            PlayerData fresh = dataStore.load(player.getUniqueId());
            fresh.setHasJoinedBefore(true);
            fresh.setWorldUid(location.getWorld().getUID());
            fresh.ensureLifeId();
            fresh.setFirstSpawn(location.getWorld().getName(), location.getX(), location.getY(), location.getZ());
            dataStore.save(player.getUniqueId(), fresh);

            journal.record(JournalEntry.Reason.FIRST_JOIN, player.getUniqueId(), playerName,
                location.getWorld().getName(), location.getX(), location.getY(), location.getZ());

            // Teleport only if still online
            Player live = Bukkit.getPlayer(player.getUniqueId());
            if (live != null && live.isOnline()) {
                live.teleport(location);
            }
        });
    }

    /**
     * Writes down an arrival that has already happened.
     *
     * <p>No teleport and no search: the player was put here by the spawn position itself, so this is
     * bookkeeping catching up with a fact rather than deciding one.
     */
    private void record(Player player, PlayerData data, ArrivalPreparation.Placement prepared) {
        Location at = prepared.location();
        World world = at.getWorld();

        switch (prepared.kind()) {
            case FIRST_JOIN -> {
                data.setHasJoinedBefore(true);
                data.setWorldUid(world.getUID());
                data.ensureLifeId();
                data.setFirstSpawn(world.getName(), at.getX(), at.getY(), at.getZ());
                dataStore.save(player.getUniqueId(), data);

                journal.record(JournalEntry.Reason.FIRST_JOIN, player.getUniqueId(), player.getName(),
                    world.getName(), at.getX(), at.getY(), at.getZ());
            }
            case PENDING_EXILE -> {
                // The exile itself was recorded when it was calculated, before this player logged
                // out of the respawn screen. Only the fact that it has now been honoured is new.
                data.clearPendingRespawn();
                data.setNeedsDelayedTeleport(false);
                data.setCalculatingRespawn(false);
                data.setWorldUid(world.getUID());
                dataStore.save(player.getUniqueId(), data);
            }
        }
    }
}
