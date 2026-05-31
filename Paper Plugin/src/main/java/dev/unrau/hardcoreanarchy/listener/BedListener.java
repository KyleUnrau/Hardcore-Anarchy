package dev.unrau.hardcoreanarchy.listener;

import com.destroystokyo.paper.event.player.PlayerSetSpawnEvent;
import dev.unrau.hardcoreanarchy.config.PluginConfig;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Prevents beds from setting the player's respawn point.
 * Sleeping itself (night-skipping) is unaffected.
 */
public class BedListener implements Listener {

    private final PluginConfig config;

    public BedListener(PluginConfig config) {
        this.config = config;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerSetSpawn(PlayerSetSpawnEvent event) {
        if (!config.isDisableBedRespawn()) return;

        if (event.getCause() == PlayerSetSpawnEvent.Cause.BED) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(config.getMsgBedsDoNotBind());
        }
    }
}
