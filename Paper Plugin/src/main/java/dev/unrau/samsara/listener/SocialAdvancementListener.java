package dev.unrau.samsara.listener;

import dev.unrau.samsara.social.SocialEvent;
import dev.unrau.samsara.social.SocialService;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;

/**
 * Scopes advancement announcements.
 *
 * <p>Only the advancements vanilla would have announced reach this at all — the event carries a
 * message for those and nothing for the rest — so what is decided here is who hears them, not which
 * ones are worth hearing about.
 *
 * <p>The component is passed through untouched, which keeps the advancement's own hover card: the
 * frame, the title and the description a client shows when you point at the name.
 */
public class SocialAdvancementListener implements Listener {

    private final SocialService social;

    public SocialAdvancementListener(SocialService social) {
        this.social = social;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        if (!social.settings().isEnabled()) return;

        Component message = event.message();
        if (message == null) return;

        Player player = event.getPlayer();
        event.message(null);
        social.audience().announce(player, SocialEvent.ADVANCEMENT, message);
    }
}
