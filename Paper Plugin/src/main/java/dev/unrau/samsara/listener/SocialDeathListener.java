package dev.unrau.samsara.listener;

import dev.unrau.samsara.social.SocialEvent;
import dev.unrau.samsara.social.SocialService;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

/**
 * Scopes a player's death message.
 *
 * <p>A death on this server is the end of a life and the start of an exile somewhere else entirely,
 * and vanilla announces it to everyone. Read from a thousand kilometres away that is not news, it is
 * a list — and it is also intelligence: a name appearing in the death list tells the whole server
 * that somebody, somewhere, is standing over loot.
 *
 * <p>So it goes to whoever was there, to the dead player, and to their contacts. The component
 * itself is untouched: vanilla's death messages carry the killer's item and the attacker's identity
 * as hover text, and rebuilding them as a string would throw all of that away.
 *
 * <p>Separate from {@link DeathListener}, which decides what dying <em>costs</em>. That one runs at
 * HIGH so its Ender chest drop lands with the vanilla drops; this runs later still and touches
 * nothing but the message.
 */
public class SocialDeathListener implements Listener {

    private final SocialService social;

    public SocialDeathListener(SocialService social) {
        this.social = social;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        if (!social.settings().isEnabled()) return;

        Component message = event.deathMessage();
        // Null already means silence — the showDeathMessages gamerule is off, or another plugin has
        // taken the message away. Either way there is nothing to scope.
        if (message == null) return;

        Player player = event.getEntity();
        event.deathMessage(null);
        social.audience().announce(player, SocialEvent.DEATH, message);
    }
}
