package dev.unrau.samsara.command;

import dev.unrau.samsara.social.SocialData;
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
import java.util.Map;
import java.util.UUID;

/**
 * {@code /ignore} and {@code /unignore} — the switch that beats everything else.
 *
 * <p>Every other rule in the social system is about whether somebody is close enough, or whether two
 * people agreed to something. This one is not a balance: it is the recipient saying no, and it wins
 * against proximity, against being a contact, and against a private message. There is no distance at
 * which an ignored player becomes audible again and no relationship that reinstates them.
 *
 * <p>It is also one-sided and unannounced. The player being ignored is never told, because a block
 * that notifies its target is an invitation to argue about it, and the arguing happens to the person
 * who wanted it to stop.
 *
 * <p>Ignoring somebody does <em>not</em> remove them as a contact. The two are different statements —
 * one is "I do not want to hear this", the other is "we are not connected" — and collapsing them
 * would mean a player could not quiet somebody down for an evening without tearing something up.
 */
public class IgnoreCommand implements CommandExecutor, TabCompleter {

    private final SocialService social;

    /** True for {@code /unignore}, so both spellings share one implementation. */
    private final boolean removing;

    public IgnoreCommand(SocialService social, boolean removing) {
        this.social = social;
        this.removing = removing;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("An ignore list belongs to a player. Run this in game.");
            return true;
        }
        if (!social.settings().isEnabled()) {
            player.sendMessage(error("The social system is disabled on this server."));
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            if (removing) {
                player.sendMessage(error("Usage: /unignore <player>"));
                return true;
            }
            list(player);
            return true;
        }

        if (removing) {
            unignore(player, args[0]);
        } else {
            ignore(player, args[0]);
        }
        return true;
    }

    private void list(Player player) {
        SocialData data = social.store().load(player.getUniqueId());
        Map<UUID, String> ignored = data.getIgnored();

        if (ignored.isEmpty()) {
            player.sendMessage(plain("You are ignoring nobody."));
            player.sendMessage(muted("/ignore <player> switches somebody off entirely — chat,"
                + " deaths, advancements, comings and goings, and private messages."));
            return;
        }

        player.sendMessage(plain("Ignoring (" + ignored.size() + "):"));
        List<UUID> sorted = new ArrayList<>(ignored.keySet());
        sorted.sort((a, b) -> social.nameOf(player, a).compareToIgnoreCase(social.nameOf(player, b)));
        for (UUID other : sorted) {
            player.sendMessage(plain("  " + social.nameOf(player, other)));
        }
        player.sendMessage(muted("/unignore <player> undoes it."));
    }

    private void ignore(Player player, String name) {
        UUID them = social.resolve(player, name);
        if (them == null) {
            player.sendMessage(error("Nobody here is called " + name + "."));
            return;
        }
        if (them.equals(player.getUniqueId())) {
            player.sendMessage(error("You cannot ignore yourself."));
            return;
        }

        String theirName = social.nameOf(player, them);
        if (!social.graph().ignore(player.getUniqueId(), them, theirName)) {
            player.sendMessage(plain("You are already ignoring " + theirName + "."));
            return;
        }

        // A question from somebody you have just switched off is a question you would never see the
        // asking of. Drop whatever was in flight rather than leaving it to expire unseen.
        social.requests().forget(player.getUniqueId(), them);

        player.sendMessage(plain("Ignoring " + theirName + "."));
        if (social.graph().areContacts(player.getUniqueId(), them)) {
            player.sendMessage(muted("They are still a contact — nothing they send reaches you, but"
                + " what you say still reaches them. /contact remove " + theirName
                + " ends that too."));
        } else {
            player.sendMessage(muted("Nothing of theirs reaches you now, at any distance."));
        }
    }

    private void unignore(Player player, String name) {
        UUID them = social.resolve(player, name);
        if (them == null) {
            player.sendMessage(error("Nobody here is called " + name + "."));
            return;
        }
        if (!social.graph().unignore(player.getUniqueId(), them)) {
            player.sendMessage(plain("You were not ignoring " + social.nameOf(player, them) + "."));
            return;
        }
        player.sendMessage(plain("No longer ignoring " + social.nameOf(player, them) + "."));
    }

    private static Component plain(String text) {
        return Component.text(text, NamedTextColor.WHITE);
    }

    private static Component muted(String text) {
        return Component.text(text, NamedTextColor.GRAY);
    }

    private static Component error(String text) {
        return Component.text(text, NamedTextColor.RED);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (!(sender instanceof Player player) || args.length != 1) return List.of();

        List<String> candidates = new ArrayList<>();
        if (removing) {
            // Only the people it would do something to: the list this command exists to shorten.
            social.store().load(player.getUniqueId()).getIgnored().keySet()
                .forEach(id -> candidates.add(social.nameOf(player, id)));
        } else {
            candidates.add("list");
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (!online.equals(player)) candidates.add(online.getName());
            }
        }

        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String candidate : candidates) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith(prefix)) matches.add(candidate);
        }
        return matches;
    }
}
