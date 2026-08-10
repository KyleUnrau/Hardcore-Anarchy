package dev.unrau.samsara.listener;

import dev.unrau.samsara.social.SocialEvent;
import dev.unrau.samsara.social.SocialService;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.audience.Audience;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Scopes ordinary chat to the people who could plausibly be in earshot.
 *
 * <p>Nothing here formats, rewrites or re-sends a message. Paper hands the event a set of viewers
 * and lets a listener take names out of it, so the message that arrives is the one the server built,
 * with whatever renderer, prefix or client-side signature it already had — the only change is who it
 * is delivered to. Anything that rebuilt the component would quietly break every other chat plugin
 * on the server, and would strip the signature that lets clients report chat.
 *
 * <p>The console is deliberately left in the set. The server log is not a place with a position in
 * the world, and an operator reading it afterwards should see the whole conversation.
 *
 * <p>Runs late and ignores cancelled events, so a mute or filter plugin has already had its say.
 */
public class SocialChatListener implements Listener {

    private final SocialService social;

    public SocialChatListener(SocialService social) {
        this.social = social;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!social.settings().isEnabled()) return;

        Player speaker = event.getPlayer();
        for (var viewers = event.viewers().iterator(); viewers.hasNext(); ) {
            Audience viewer = viewers.next();
            if (viewer instanceof Player player
                && !social.audience().canSee(player, speaker, SocialEvent.CHAT)) {
                viewers.remove();
            }
        }
    }
}
