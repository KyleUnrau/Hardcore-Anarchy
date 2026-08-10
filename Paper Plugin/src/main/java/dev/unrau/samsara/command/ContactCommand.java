package dev.unrau.samsara.command;

import dev.unrau.samsara.config.SocialConfig;
import dev.unrau.samsara.social.ContactRequests;
import dev.unrau.samsara.social.SocialData;
import dev.unrau.samsara.social.SocialGraph;
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
 * {@code /contact} — the whole of the consent flow, in one command.
 *
 * <p>Adding somebody sends them a question. Nothing about the asker becomes visible to the asked, or
 * the asked to the asker, until it is answered — which is the entire reason a request exists rather
 * than a one-sided "add". A contact list that could be filled in unilaterally would be a way to
 * follow people who never agreed to be followed, on a server whose social rules are otherwise built
 * around distance.
 *
 * <p>Removal is unilateral, because leaving a relationship needs no permission from the other party.
 * It takes the relationship apart on both sides and remembers that it was taken apart, so standing
 * near each other afterwards does not quietly rebuild it.
 *
 * <p>{@code /contacts} is the same command; both spellings read naturally in front of different
 * subcommands, and neither is worth making a player remember.
 */
public class ContactCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS =
        List.of("add", "accept", "decline", "remove", "list", "requests", "auto", "help");

    private final SocialService social;

    public ContactCommand(SocialService social) {
        this.social = social;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Contacts belong to a player. Run this in game.");
            return true;
        }

        SocialConfig settings = social.settings();
        if (!settings.isEnabled() || !settings.isContactsEnabled()) {
            player.sendMessage(error("Contacts are disabled on this server."));
            return true;
        }

        String action = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "list" -> list(player);
            case "add", "request" -> add(player, argument(args, 1));
            case "accept", "yes" -> accept(player, argument(args, 1));
            case "decline", "deny", "no" -> decline(player, argument(args, 1));
            case "remove", "delete" -> remove(player, argument(args, 1));
            case "requests", "pending" -> requests(player);
            case "auto" -> auto(player, args);
            default -> usage(player);
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Reading
    // -------------------------------------------------------------------------

    private void list(Player player) {
        SocialConfig settings = social.settings();
        SocialData data = social.store().load(player.getUniqueId());
        Map<UUID, String> contacts = data.getContacts();

        if (contacts.isEmpty()) {
            player.sendMessage(plain("You have no contacts. Everything you say reaches"
                + " whoever is within " + settings.getChatRadius() + " blocks of you."));
            player.sendMessage(muted("/contact add <player> asks somebody to change that."));
            autoNotice(player, data, settings);
            return;
        }

        player.sendMessage(plain("Contacts (" + contacts.size() + "):"));
        for (UUID contactId : sortedByName(player, contacts.keySet())) {
            Player online = Bukkit.getPlayer(contactId);
            String name = social.nameOf(player, contactId);
            player.sendMessage(online != null
                ? plain("  " + name).append(muted("  online"))
                : plain("  " + name).append(muted("  offline")));
        }
        player.sendMessage(muted("They see your chat, deaths, advancements and comings and goings"
            + " at any distance, and you see theirs. Nothing else."));
        autoNotice(player, data, settings);
    }

    /**
     * Says out loud that contacts can form on their own, when they can.
     *
     * <p>The server has this on by default, so for most players it is true of them without their
     * ever having asked for it — and a setting nobody was told about is not one they consented to.
     * Printed wherever a player is already looking at their contacts, which is where the question
     * "how did that happen" gets asked.
     */
    private void autoNotice(Player player, SocialData data, SocialConfig settings) {
        if (!settings.isAutoContactsEnabled()) return;
        if (!data.autoContactsEnabled(settings.isAutoContactsDefaultOn())) return;

        player.sendMessage(muted("Contacts can also form on their own with people you spend real"
            + " time beside, if they allow it too. /contacts auto off stops that."));
    }

    private void requests(Player player) {
        ContactRequests requests = social.requests();
        List<UUID> incoming = requests.pendingFor(player.getUniqueId());
        List<UUID> outgoing = requests.sentBy(player.getUniqueId());

        if (incoming.isEmpty() && outgoing.isEmpty()) {
            player.sendMessage(plain("No contact requests."));
            return;
        }

        for (UUID from : incoming) {
            player.sendMessage(plain(social.nameOf(player, from) + " has asked to be your contact.")
                .append(muted("  /contact accept " + social.nameOf(player, from))));
        }
        for (UUID to : outgoing) {
            player.sendMessage(muted("Waiting on " + social.nameOf(player, to) + "."));
        }
    }

    // -------------------------------------------------------------------------
    // The consent flow
    // -------------------------------------------------------------------------

    private void add(Player player, String name) {
        if (name == null) {
            player.sendMessage(error("Usage: /contact add <player>"));
            return;
        }

        Player target = Bukkit.getPlayerExact(name);
        if (target == null) {
            // Requests are asked and answered between two people who are both here. A question left
            // for somebody who is offline is one they would answer days later, to a request they
            // never saw arrive.
            player.sendMessage(error(name + " is not online."));
            return;
        }
        if (target.equals(player)) {
            player.sendMessage(error("You are your own contact by definition."));
            return;
        }

        UUID me = player.getUniqueId();
        UUID them = target.getUniqueId();

        if (social.graph().areContacts(me, them)) {
            player.sendMessage(plain(target.getName() + " is already a contact."));
            return;
        }
        if (social.store().load(me).ignores(them)) {
            player.sendMessage(error("You are ignoring " + target.getName()
                + ". Use /unignore " + target.getName() + " first."));
            return;
        }
        if (social.store().load(them).ignores(me)) {
            player.sendMessage(error(target.getName() + " is not accepting contact requests from you."));
            return;
        }

        SocialConfig settings = social.settings();
        ContactRequests.SendOutcome outcome = social.requests().send(me, player.getName(), them,
            settings.getRequestExpirySeconds(), settings.getRequestCooldownSeconds());

        switch (outcome) {
            case SENT -> {
                player.sendMessage(plain("Asked " + target.getName() + " to be a contact."));
                player.sendMessage(muted("They have "
                    + (settings.getRequestExpirySeconds() / 60) + " minutes or so to answer."));

                target.sendMessage(plain(player.getName() + " has asked to be your contact."));
                target.sendMessage(muted("You would see each other's chat, deaths, advancements and"
                    + " comings and goings at any distance — nothing more."));
                target.sendMessage(muted("/contact accept " + player.getName()
                    + "   or   /contact decline " + player.getName()));
            }
            // They had already asked. Sending back is an answer, not a second question.
            case RECIPROCATED -> link(player, target);
            case ALREADY_PENDING -> player.sendMessage(plain("You have already asked "
                + target.getName() + "."));
            case RECENTLY_DECLINED -> player.sendMessage(error(target.getName()
                + " has declined. Leave it a while before asking again."));
        }
    }

    private void accept(Player player, String name) {
        if (name == null) {
            player.sendMessage(error("Usage: /contact accept <player>"));
            return;
        }

        Player target = Bukkit.getPlayerExact(name);
        if (target == null) {
            player.sendMessage(error(name + " is not online."));
            return;
        }
        if (!social.requests().take(player.getUniqueId(), target.getUniqueId())) {
            player.sendMessage(error(target.getName() + " has not asked to be your contact."));
            return;
        }
        link(player, target);
    }

    private void decline(Player player, String name) {
        if (name == null) {
            player.sendMessage(error("Usage: /contact decline <player>"));
            return;
        }

        UUID them = social.resolve(player, name);
        if (them == null || !social.requests().hasPending(player.getUniqueId(), them)) {
            player.sendMessage(error(name + " has not asked to be your contact."));
            return;
        }

        social.requests().decline(player.getUniqueId(), them, social.settings().getRequestCooldownSeconds());
        player.sendMessage(plain("Declined."));

        // The asker is told, because silence would have them wondering whether it arrived. What they
        // are not told is anything about why.
        Player target = Bukkit.getPlayer(them);
        if (target != null) {
            target.sendMessage(muted(player.getName() + " declined your contact request."));
        }
    }

    /** The one place a manual contact is made, from either the accept path or a crossed request. */
    private void link(Player player, Player target) {
        SocialGraph.LinkOutcome outcome = social.graph().link(
            player.getUniqueId(), player.getName(), target.getUniqueId(), target.getName());

        switch (outcome) {
            case LINKED -> {
                // Manual consent settles both the refusal and the severance that may have stood
                // between them: this is exactly the deliberate act those were waiting for. Any
                // request still standing in either direction has been answered by this.
                social.requests().forget(player.getUniqueId(), target.getUniqueId());
                social.requests().clearDecline(player.getUniqueId(), target.getUniqueId());
                player.sendMessage(plain(target.getName() + " is now a contact."));
                target.sendMessage(plain(player.getName() + " is now a contact."));
            }
            case ALREADY_CONTACTS -> player.sendMessage(plain(target.getName()
                + " is already a contact."));
            case FULL -> player.sendMessage(error("One of you has as many contacts as the server"
                + " allows (" + social.settings().getMaxContacts() + ")."));
        }
    }

    private void remove(Player player, String name) {
        if (name == null) {
            player.sendMessage(error("Usage: /contact remove <player>"));
            return;
        }

        UUID them = social.resolve(player, name);
        if (them == null) {
            player.sendMessage(error("Nobody here is called " + name + "."));
            return;
        }
        if (!social.graph().areContacts(player.getUniqueId(), them)) {
            player.sendMessage(error(social.nameOf(player, them) + " is not a contact."));
            return;
        }

        String theirName = social.nameOf(player, them);
        social.graph().unlink(player.getUniqueId(), them);

        player.sendMessage(plain(theirName + " is no longer a contact."));
        player.sendMessage(muted("Neither of you sees the other from a distance any more, and"
            + " standing together will not make it happen again on its own."));

        // Told, because the visibility they had a moment ago is gone and they will notice. Removal
        // is unilateral; being quiet about it would not make it less so.
        Player target = Bukkit.getPlayer(them);
        if (target != null) {
            target.sendMessage(muted(player.getName() + " is no longer a contact."));
        }
    }

    // -------------------------------------------------------------------------
    // Automatic contacts
    // -------------------------------------------------------------------------

    private void auto(Player player, String[] args) {
        SocialConfig settings = social.settings();
        if (!settings.isAutoContactsEnabled()) {
            player.sendMessage(plain("This server does not form contacts automatically."));
            return;
        }

        String choice = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : null;
        if (choice == null) {
            boolean on = social.store().load(player.getUniqueId())
                .autoContactsEnabled(settings.isAutoContactsDefaultOn());
            player.sendMessage(plain("Automatic contacts are " + (on ? "on" : "off") + "."));
            player.sendMessage(muted("With this on, spending about "
                + (settings.getAutoRequiredSeconds() / 60) + " minutes within "
                + settings.getAutoCloseRadius() + " blocks of somebody who also has it on makes the"
                + " two of you contacts."));
            player.sendMessage(muted("It need not be in one stretch. Further out it counts for less,"
                + " nothing at all past " + settings.getAutoRadius() + " blocks, and time apart"
                + " undoes it. /contacts auto on|off"));
            return;
        }

        switch (choice) {
            case "on", "true", "yes" -> {
                social.graph().setAutoContacts(player.getUniqueId(), true);
                player.sendMessage(plain("Automatic contacts on."));
                player.sendMessage(muted("Contacts may now form with people you spend real time"
                    + " around, if they have allowed it too."));
            }
            case "off", "false", "no" -> {
                social.graph().setAutoContacts(player.getUniqueId(), false);
                player.sendMessage(plain("Automatic contacts off."));
                player.sendMessage(muted("The contacts you already have are untouched."));
            }
            case "allow" -> allowAuto(player, argument(args, 2));
            default -> player.sendMessage(error("Usage: /contacts auto <on|off>"));
        }
    }

    /**
     * Lifts this player's own refusal to have a contact re-form with somebody.
     *
     * <p>The deliberate act that undoes a deliberate removal. It is one-sided by design: the other
     * player's side of the severance stands until they lift it, so neither of them can decide alone
     * that the falling-out is over.
     */
    private void allowAuto(Player player, String name) {
        if (name == null) {
            player.sendMessage(error("Usage: /contacts auto allow <player>"));
            return;
        }

        UUID them = social.resolve(player, name);
        if (them == null) {
            player.sendMessage(error("Nobody here is called " + name + "."));
            return;
        }
        if (!social.graph().allowAuto(player.getUniqueId(), them)) {
            player.sendMessage(plain("Nothing was stopping a contact forming with "
                + social.nameOf(player, them) + "."));
            return;
        }

        player.sendMessage(plain("A contact may form with " + social.nameOf(player, them)
            + " again."));
        player.sendMessage(muted("If they removed you, their own side of it still stands until"
            + " they say the same."));
    }

    // -------------------------------------------------------------------------
    // Plumbing
    // -------------------------------------------------------------------------

    private void usage(Player player) {
        player.sendMessage(plain("Contacts — people you stay visible to at any distance."));
        player.sendMessage(muted("/contacts                    who they are"));
        player.sendMessage(muted("/contact add <player>        ask somebody"));
        player.sendMessage(muted("/contact accept <player>     say yes"));
        player.sendMessage(muted("/contact decline <player>    say no"));
        player.sendMessage(muted("/contact remove <player>     end it, from either side"));
        player.sendMessage(muted("/contact requests            what is outstanding"));
        player.sendMessage(muted("/contacts auto on|off        let them form on their own"));
    }

    private List<UUID> sortedByName(Player viewer, Iterable<UUID> ids) {
        List<UUID> sorted = new ArrayList<>();
        ids.forEach(sorted::add);
        sorted.sort((a, b) -> social.nameOf(viewer, a).compareToIgnoreCase(social.nameOf(viewer, b)));
        return sorted;
    }

    private static String argument(String[] args, int index) {
        return args.length > index ? args[index] : null;
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
        if (!(sender instanceof Player player)) return List.of();

        if (args.length == 1) {
            return matching(SUBCOMMANDS, args[0]);
        }
        if (args.length != 2) {
            return args.length == 3 && args[0].equalsIgnoreCase("auto")
                ? matching(onlineNames(player), args[2])
                : List.of();
        }

        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "accept", "decline" -> matching(names(player,
                social.requests().pendingFor(player.getUniqueId())), args[1]);
            case "remove" -> matching(names(player,
                social.store().load(player.getUniqueId()).getContacts().keySet()), args[1]);
            case "add" -> matching(onlineNames(player), args[1]);
            case "auto" -> matching(List.of("on", "off", "allow"), args[1]);
            default -> List.of();
        };
    }

    private List<String> names(Player viewer, Iterable<UUID> ids) {
        List<String> names = new ArrayList<>();
        ids.forEach(id -> names.add(social.nameOf(viewer, id)));
        return names;
    }

    private static List<String> onlineNames(Player viewer) {
        List<String> names = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.equals(viewer)) names.add(online.getName());
        }
        return names;
    }

    private static List<String> matching(List<String> candidates, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String candidate : candidates) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith(lower)) matches.add(candidate);
        }
        return matches;
    }
}
