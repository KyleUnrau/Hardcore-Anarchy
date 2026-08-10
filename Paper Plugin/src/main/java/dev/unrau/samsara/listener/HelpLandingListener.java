package dev.unrau.samsara.listener;

import dev.unrau.samsara.config.PluginConfig;
import dev.unrau.samsara.help.ServerHelp;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Locale;

/**
 * Gives a bare {@code /help} to the server, and leaves every other form of it alone.
 *
 * <p>A player who types {@code /help} on a server like this one is almost never asking for the
 * syntax of {@code /advancement}; they are asking what this place is. So {@code /help} on its own
 * answers that question, and its last line points at {@code /help 1} for the command index that
 * would otherwise have been printed.
 *
 * <p>Nothing else is intercepted. {@code /help 1}, {@code /help <command>} and {@code /help <topic>}
 * all reach Paper's help command untouched, which is the reason this is done here rather than by
 * registering a competing {@code /help}: the real help command keeps working, complete with the
 * topics this plugin files alongside its own.
 */
public class HelpLandingListener implements Listener {

    private final PluginConfig config;
    private final ServerHelp serverHelp;

    public HelpLandingListener(PluginConfig config, ServerHelp serverHelp) {
        this.config = config;
        this.serverHelp = serverHelp;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!config.getPresentation().isHelpLandingPage()) return;
        if (!isBareHelp(event.getMessage())) return;

        Player player = event.getPlayer();
        // A player who is not allowed to run /help is not handed the page by the back door; letting
        // the command through gets them the same refusal they would get without this plugin.
        if (!player.hasPermission("bukkit.command.help")) return;

        event.setCancelled(true);
        serverHelp.sendLandingPage(player);
    }

    /**
     * True for {@code /help} and {@code /?} with nothing after them.
     *
     * <p>Namespaced forms count — {@code /bukkit:help} is the same request — but anything with an
     * argument does not, however harmless it looks. {@code /help 1} is the command index and has to
     * stay the command index, or the landing page becomes a page a player cannot get past.
     */
    static boolean isBareHelp(String message) {
        String line = message.startsWith("/") ? message.substring(1) : message;
        line = line.trim();
        if (line.isEmpty() || line.indexOf(' ') >= 0) return false;

        String name = line.toLowerCase(Locale.ROOT);
        int namespace = name.indexOf(':');
        if (namespace >= 0) {
            name = name.substring(namespace + 1);
        }
        return name.equals("help") || name.equals("?");
    }
}
