package dev.unrau.hardcoreanarchy.listener;

import dev.unrau.hardcoreanarchy.config.PluginConfig;
import dev.unrau.hardcoreanarchy.data.PlayerData;
import dev.unrau.hardcoreanarchy.data.PlayerDataStore;
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
    private final PluginConfig config;
    private final PlayerDataStore dataStore;

    public RespawnListener(JavaPlugin plugin, PluginConfig config, PlayerDataStore dataStore) {
        this.plugin = plugin;
        this.config = config;
        this.dataStore = dataStore;
    }

    // HIGHEST so we override any other plugin that might set respawn location
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        PlayerData data = dataStore.load(player.getUniqueId());

        if (!data.isHasJoinedBefore()) {
            // Edge case: first-join logic handles initial spawn; nothing to do here
            return;
        }

        if (data.isHasPendingRespawn()) {
            // Exile location is ready — use it
            World world = Bukkit.getWorld(data.getPendingRespawnWorld());
            if (world != null) {
                Location exile = new Location(world,
                    data.getPendingRespawnX(), data.getPendingRespawnY(), data.getPendingRespawnZ());
                event.setRespawnLocation(exile);
                data.clearPendingRespawn();
                data.setCalculatingRespawn(false);
                dataStore.save(player.getUniqueId(), data);

                // Delay the message one tick so it appears after the respawn animation
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    Player live = Bukkit.getPlayer(player.getUniqueId());
                    if (live != null && live.isOnline()) {
                        live.sendMessage(config.getMsgDeathScattered());
                    }
                }, 1L);
                return;
            }
        }

        if (data.isCalculatingRespawn()) {
            // Async search is still running — respawn at world spawn temporarily,
            // then teleport when the calculation finishes (handled in ExileSpawnService)
            World world = Bukkit.getWorld(config.getWorldName());
            if (world != null) {
                event.setRespawnLocation(world.getSpawnLocation());
            }
            data.setNeedsDelayedTeleport(true);
            dataStore.save(player.getUniqueId(), data);

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Player live = Bukkit.getPlayer(player.getUniqueId());
                if (live != null && live.isOnline()) {
                    live.sendMessage(config.getMsgCalculatingExile());
                }
            }, 1L);
        }
        // If neither flag is set (e.g. normal respawn after first join teleport), let vanilla handle it
    }
}
