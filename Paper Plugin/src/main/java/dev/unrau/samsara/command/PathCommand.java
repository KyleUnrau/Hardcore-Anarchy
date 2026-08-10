package dev.unrau.samsara.command;

import dev.unrau.samsara.path.PathIndex;
import dev.unrau.samsara.path.PathService;
import dev.unrau.samsara.path.PlayerPath;
import dev.unrau.samsara.path.SharedBeginnings;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /path} — the whole of a player's dealings with their own existences.
 *
 * <p>Six verbs and no menus. Everything a player does here is destructive, slow, or both, and every
 * one of those is easier to be sure about as a line of text than as a click: a screen of item icons
 * is exactly the wrong way to be shown the belongings of a life you are about to end.
 *
 * <pre>
 *   /path                        which paths you hold
 *   /path switch &lt;name&gt;          leave this existence and take up another
 *   /path new &lt;name&gt; [players]   begin one, alone or alongside people who agree
 *   /path accept &lt;player&gt; [name] agree to a shared beginning
 *   /path decline [player]       refuse one, or withdraw your own
 *   /path rename &lt;old&gt; &lt;new&gt;     rename one
 *   /path abandon &lt;name&gt; confirm destroy one, and drop what it was carrying
 * </pre>
 */
public class PathCommand implements CommandExecutor, TabCompleter {

    private static final List<String> VERBS = List.of(
        "switch", "new", "accept", "decline", "cancel", "rename", "abandon");

    private final PathService paths;

    public PathCommand(PathService paths) {
        this.paths = paths;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only a player has paths to walk.");
            return true;
        }
        if (!paths.settings().isEnabled()) {
            sender.sendMessage("Paths are switched off on this server; you have the one you are on.");
            return true;
        }

        if (args.length == 0) {
            sendList(player);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> sendList(player);

            case "switch" -> {
                if (args.length < 2) {
                    player.sendMessage("Usage: /path switch <name>");
                    return true;
                }
                report(player, paths.switchTo(player, args[1]));
            }

            case "new" -> {
                if (args.length < 2) {
                    player.sendMessage("Usage: /path new <name> [player...]");
                    player.sendMessage("Naming players asks them first; nobody is moved until"
                        + " everyone agrees.");
                    return true;
                }
                handleNew(player, args);
            }

            case "accept" -> {
                if (args.length < 2) {
                    player.sendMessage("Usage: /path accept <player> [name for your new path]");
                    return true;
                }
                report(player, paths.accept(player, args[1], args.length >= 3 ? args[2] : null));
            }

            // Refusing somebody else's offer and withdrawing your own are the same act — the offer
            // ends and nothing has happened — so they are one code path under two words.
            case "decline", "cancel" -> report(player,
                paths.decline(player, args.length >= 2 ? args[1] : null));

            case "rename" -> {
                if (args.length < 3) {
                    player.sendMessage("Usage: /path rename <old name> <new name>");
                    return true;
                }
                report(player, paths.rename(player, args[1], args[2]));
            }

            case "abandon" -> {
                if (args.length < 2) {
                    player.sendMessage("Usage: /path abandon <name> confirm");
                    return true;
                }
                boolean confirmed = args.length >= 3 && args[2].equalsIgnoreCase("confirm");
                report(player, paths.abandon(player, args[1], confirmed));
            }

            default -> {
                player.sendMessage("Usage: /path [switch|new|accept|decline|rename|abandon]");
                player.sendMessage("/path on its own lists the paths you hold.");
            }
        }
        return true;
    }

    /** {@code /path new <name> [player...]} — alone with one argument, shared with more. */
    private void handleNew(Player player, String[] args) {
        if (args.length == 2) {
            report(player, paths.begin(player, args[1]));
            return;
        }

        List<Player> invited = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            Player one = Bukkit.getPlayerExact(args[i]);
            if (one == null) {
                player.sendMessage("'" + args[i] + "' is not online. Everyone has to be here to"
                    + " agree, and to be put down together.");
                return;
            }
            if (invited.contains(one)) continue;
            invited.add(one);
        }
        report(player, paths.propose(player, args[1], invited));
    }

    /**
     * The list, which is also the answer to "what am I allowed to do next": the count against the
     * limit is the fact every other command in this family refers back to.
     */
    private void sendList(Player player) {
        PathIndex index = paths.indexOf(player.getUniqueId());
        PlayerPath active = index.active();

        player.sendMessage("Your paths — " + index.size() + " of "
            + paths.settings().getMaxPaths() + ":");
        for (PlayerPath path : index.paths()) {
            StringBuilder line = new StringBuilder("  ");
            line.append(path == active ? "> " : "  ").append(path.name());
            if (path == active) line.append("  (walking this one)");
            String age = ageOf(path);
            if (age != null) line.append("  begun ").append(age);
            if (!path.companions().isEmpty()) {
                line.append("  with ").append(String.join(", ", path.companions()));
            }
            player.sendMessage(line.toString());
        }

        SharedBeginnings.Party party = paths.beginnings().partyOf(player.getUniqueId());
        if (party != null) {
            boolean mine = party.initiator().equals(player.getUniqueId());
            player.sendMessage(mine
                ? "Waiting on: " + String.join(", ", namesOutstanding(party))
                    + ". /path cancel withdraws it."
                : party.initiatorName() + " has asked you to begin '" + party.proposedName()
                    + "' together. /path accept " + party.initiatorName() + " [name]");
        }

        player.sendMessage("Dying does not cost you a path — the path receives the new life."
            + " Abandoning one does, and drops everything it was carrying.");
    }

    private List<String> namesOutstanding(SharedBeginnings.Party party) {
        List<String> names = new ArrayList<>();
        for (var member : party.outstanding()) names.add(party.nameOf(member));
        return names;
    }

    /** How long ago a path began, in the roundest unit that still says something. */
    private String ageOf(PlayerPath path) {
        if (path.createdAt() <= 0) return null;

        Duration since = Duration.between(Instant.ofEpochMilli(path.createdAt()), Instant.now());
        long days = since.toDays();
        if (days >= 1) return days + (days == 1 ? " day ago" : " days ago");
        long hours = since.toHours();
        if (hours >= 1) return hours + (hours == 1 ? " hour ago" : " hours ago");
        long minutes = Math.max(0, since.toMinutes());
        return minutes + (minutes == 1 ? " minute ago" : " minutes ago");
    }

    private void report(Player player, PathService.Outcome outcome) {
        player.sendMessage(outcome.message());
    }

    // -------------------------------------------------------------------------
    // Completion
    // -------------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player) || !paths.settings().isEnabled()) {
            return List.of();
        }

        if (args.length == 1) {
            return matching(VERBS, args[0]);
        }

        String verb = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            return switch (verb) {
                // Never completes the path being walked for switch: it is the one name that cannot
                // be an answer, and offering it invites the refusal.
                case "switch" -> matching(otherPathNames(player), args[1]);
                case "abandon", "rename" -> matching(otherPathNames(player), args[1]);
                case "accept", "decline" -> matching(initiatorOf(player), args[1]);
                default -> List.of();
            };
        }

        // /path new <name> <player...> — everybody online except the people already named.
        if (verb.equals("new") && args.length >= 3) {
            List<String> online = new ArrayList<>();
            for (Player one : Bukkit.getOnlinePlayers()) {
                if (one.equals(player)) continue;
                if (alreadyNamed(args, one.getName())) continue;
                online.add(one.getName());
            }
            return matching(online, args[args.length - 1]);
        }

        if (verb.equals("abandon") && args.length == 3) {
            return matching(List.of("confirm"), args[2]);
        }
        return List.of();
    }

    /** Every path except the one being walked — the only ones any verb here can act on. */
    private List<String> otherPathNames(Player player) {
        List<String> names = new ArrayList<>();
        for (PlayerPath path : paths.indexOf(player.getUniqueId()).dormant()) {
            names.add(path.name());
        }
        return names;
    }

    private List<String> initiatorOf(Player player) {
        SharedBeginnings.Party party = paths.beginnings().partyOf(player.getUniqueId());
        return party == null ? List.of() : List.of(party.initiatorName());
    }

    private boolean alreadyNamed(String[] args, String name) {
        for (int i = 2; i < args.length - 1; i++) {
            if (args[i].equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    private List<String> matching(List<String> candidates, String prefix) {
        String typed = prefix.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String candidate : candidates) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith(typed)) matches.add(candidate);
        }
        return matches;
    }
}
