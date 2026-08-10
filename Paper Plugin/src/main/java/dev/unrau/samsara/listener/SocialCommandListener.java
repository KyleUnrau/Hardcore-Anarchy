package dev.unrau.samsara.listener;

import dev.unrau.samsara.social.SocialEvent;
import dev.unrau.samsara.social.SocialService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Locale;
import java.util.Set;

/**
 * Takes the vanilla commands that talk over everybody's head and hands them to the social layer.
 *
 * <p>This exists because of one hole. {@code /msg} and {@code /me} are the server's own commands,
 * registered before any plugin loads, and a plugin command of the same name does not displace them —
 * so without this, {@code /ignore} could be walked straight around by typing {@code /msg}, and
 * {@code /me} would remain a global broadcast on a server where chat is not. Intercepting the
 * command line before it is dispatched is the only way to be sure which code answers, and it catches
 * the namespaced forms — {@code /minecraft:msg} — for the same reason.
 *
 * <p>{@code /say} is left alone on purpose: it is an operator's broadcast and is meant to be one.
 */
public class SocialCommandListener implements Listener {

    /** Every label vanilla accepts for a private message. */
    private static final Set<String> MESSAGE_LABELS = Set.of("msg", "tell", "w", "whisper");

    /** Vanilla's emote, which is chat wearing a different hat. */
    private static final Set<String> EMOTE_LABELS = Set.of("me");

    private final SocialService social;

    public SocialCommandListener(SocialService social) {
        this.social = social;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!social.settings().isEnabled()) return;

        String line = event.getMessage();
        if (line.length() < 2 || line.charAt(0) != '/') return;

        int space = line.indexOf(' ');
        String label = (space < 0 ? line.substring(1) : line.substring(1, space)).toLowerCase(Locale.ROOT);
        String rest = space < 0 ? "" : line.substring(space + 1).trim();

        // Only the namespace the interception is about. A command from any other plugin that happens
        // to end in ':msg' is that plugin's business.
        if (label.startsWith("minecraft:")) {
            label = label.substring("minecraft:".length());
        }

        Player player = event.getPlayer();

        if (MESSAGE_LABELS.contains(label)) {
            event.setCancelled(true);
            message(player, rest);
        } else if (EMOTE_LABELS.contains(label)) {
            event.setCancelled(true);
            emote(player, rest);
        }
    }

    private void message(Player player, String rest) {
        int space = rest.indexOf(' ');
        if (space < 0) {
            player.sendMessage(Component.text("Usage: /msg <player> <message>", NamedTextColor.RED));
            return;
        }
        social.messages().send(player, rest.substring(0, space), rest.substring(space + 1).trim());
    }

    /**
     * Re-sends {@code /me} through the chat audience, in vanilla's own component.
     *
     * <p>{@code chat.type.emote} is the key the game uses, so this renders as the asterisk line a
     * player expects and does it in their client's language.
     */
    private void emote(Player player, String action) {
        if (action.isEmpty()) {
            player.sendMessage(Component.text("Usage: /me <action>", NamedTextColor.RED));
            return;
        }
        social.audience().announce(player, SocialEvent.CHAT,
            Component.translatable("chat.type.emote", player.displayName(), Component.text(action)));
    }
}
