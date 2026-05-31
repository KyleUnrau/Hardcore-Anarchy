package dev.unrau.hardcoreanarchy.listener;

import com.destroystokyo.paper.event.player.PlayerSetSpawnEvent;
import dev.unrau.hardcoreanarchy.config.PluginConfig;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Prevents respawn anchors from setting the player's respawn point.
 * The anchor block's charge/explosion mechanics are unaffected.
 */
public class RespawnAnchorListener implements Listener {

    private final PluginConfig config;

    public RespawnAnchorListener(PluginConfig config) {
        this.config = config;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerSetSpawn(PlayerSetSpawnEvent event) {
        if (!config.isDisableRespawnAnchorRespawn()) return;

        if (event.getCause() == PlayerSetSpawnEvent.Cause.RESPAWN_ANCHOR) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(config.getMsgAnchorsDoNotBind());
        }
    }
}
