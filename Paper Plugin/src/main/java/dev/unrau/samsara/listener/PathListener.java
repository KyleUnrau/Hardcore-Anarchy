package dev.unrau.samsara.listener;

import dev.unrau.samsara.path.PathService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Settles which of a player's existences is in front of the server, and lets go of it afterwards.
 *
 * <p>The join runs at the lowest priority there is, before any other handler in this plugin. Every
 * one of them — the exile system, the announcement, the End's recovery of an interrupted journey —
 * reads the player's record to decide what to do, and a player who logged out mid-switch has a
 * record that describes the wrong existence until {@link PathService#onJoin} has put it right.
 *
 * <p>The quit runs at the highest, after the departure has been announced: working out who should be
 * told requires knowing what path they were walking.
 */
public class PathListener implements Listener {

    private final PathService paths;

    public PathListener(PathService paths) {
        this.paths = paths;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        paths.onJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        paths.onQuit(event.getPlayer());
    }
}
