package dev.unrau.samsara.listener;

import dev.unrau.samsara.path.PathMessages;
import dev.unrau.samsara.path.PathService;
import dev.unrau.samsara.social.SocialEvent;
import dev.unrau.samsara.social.SocialService;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Owns what the server says when somebody arrives or leaves, and to whom.
 *
 * <p>Two independent decisions meet here, which is why they are in one listener rather than two
 * fighting over the same field on the same event. <b>What is said</b> comes from
 * {@link PathMessages}: a player connecting is entering one of several existences they keep, not
 * "joining the game", and with paths switched off the wording is vanilla's own. <b>Who is told</b>
 * comes from {@link dev.unrau.samsara.social.SocialAudience}: whoever is standing near enough to
 * have noticed, plus their contacts.
 *
 * <p>A message another plugin has already silenced stays silent. This decides the words of an
 * announcement and its audience — never whether one is made at all.
 *
 * <p>When the wording is left to vanilla, the component the server built is re-sent unchanged rather
 * than rebuilt, because vanilla writes it as a translatable component and re-sending it keeps every
 * client's own language.
 *
 * <p>The join announcement waits a tick. Where a player wakes up is settled during the login
 * handshake by {@link dev.unrau.samsara.service.ArrivalPreparation}, but the fallback path places
 * them after the join instead — and announcing before that has happened would tell whoever happens
 * to be standing near the world spawn, which is the one place on this server nobody lives. A tick
 * costs nothing and lets the placement land first.
 */
public class SocialPresenceListener implements Listener {

    private final JavaPlugin plugin;
    private final SocialService social;
    private final PathService paths;
    private final PathMessages messages;

    public SocialPresenceListener(JavaPlugin plugin, SocialService social, PathService paths,
                                  PathMessages messages) {
        this.plugin = plugin;
        this.social = social;
        this.paths = paths;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        social.onJoin(player);

        Component message = messages.join(player, activePathOf(player), event.joinMessage());
        if (message == null) {
            event.joinMessage(null);
            return;
        }

        if (!social.settings().isEnabled()) {
            // Nobody is scoping anything; the server says it to everybody, as it always did.
            event.joinMessage(message);
            return;
        }

        event.joinMessage(null);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            social.audience().announce(player, SocialEvent.JOIN, message);
        });
    }

    /**
     * Announces the departure while the player is still online, then lets go of everything held for
     * them. The order matters: resolving who should be told needs their contacts, and their contacts
     * are about to be written to disk and dropped from memory.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        Component message = messages.quit(player, activePathOf(player), event.quitMessage());
        if (message == null) {
            event.quitMessage(null);
        } else if (!social.settings().isEnabled()) {
            event.quitMessage(message);
        } else {
            event.quitMessage(null);
            social.audience().announce(player, SocialEvent.QUIT, message);
        }

        social.onQuit(player);
    }

    /**
     * What the player's path is called, or null when paths are switched off — in which case nothing
     * asks for it, because the wording stays vanilla's.
     */
    private String activePathOf(Player player) {
        return messages.isEnabled() ? paths.activeNameOf(player.getUniqueId()) : null;
    }
}
