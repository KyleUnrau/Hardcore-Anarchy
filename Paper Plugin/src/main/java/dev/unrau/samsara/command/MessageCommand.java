package dev.unrau.samsara.command;

import dev.unrau.samsara.social.SocialService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /msg} and {@code /reply}.
 *
 * <p>The work is all in {@link dev.unrau.samsara.social.MessageService}, because this is not the
 * only door into it: vanilla owns the {@code /msg} label on a Paper server, so
 * {@link dev.unrau.samsara.listener.SocialCommandListener} intercepts the typed line as well. Two
 * entry points, one implementation — otherwise the version a player reaches would depend on which
 * command happened to win a registration race, and one of them would not know about {@code /ignore}.
 */
public class MessageCommand implements CommandExecutor, TabCompleter {

    private final SocialService social;

    /** True for {@code /reply}, which takes no recipient because it already has one. */
    private final boolean replying;

    public MessageCommand(SocialService social, boolean replying) {
        this.social = social;
        this.replying = replying;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Private messages are between players. Use /tell from the console.");
            return true;
        }

        if (replying) {
            if (args.length == 0) {
                player.sendMessage(error("Usage: /reply <message>"));
                return true;
            }
            social.messages().reply(player, String.join(" ", args));
            return true;
        }

        if (args.length < 2) {
            player.sendMessage(error("Usage: /msg <player> <message>"));
            return true;
        }
        social.messages().send(player, args[0],
            String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)));
        return true;
    }

    private static Component error(String text) {
        return Component.text(text, NamedTextColor.RED);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (replying || !(sender instanceof Player player) || args.length != 1) return List.of();

        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.equals(player)) continue;
            if (online.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                matches.add(online.getName());
            }
        }
        return matches;
    }
}
