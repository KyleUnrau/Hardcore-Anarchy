package dev.unrau.samsara.help;

import dev.unrau.samsara.config.PathConfig;
import dev.unrau.samsara.config.PluginConfig;
import dev.unrau.samsara.config.PresentationConfig;
import dev.unrau.samsara.config.SocialConfig;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.help.HelpMap;
import org.bukkit.util.ChatPaginator;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

/**
 * What the server says about itself when a player asks.
 *
 * <p>The whole of this class is text, and it is written to one rule: a player should be able to
 * understand what is different here without being handed the parts that are worth discovering. So
 * death, respawn and strongholds are stated plainly — a player who learns those by dying has been
 * ambushed, not challenged — and so is what each kind of End door does, since a wrong guess out
 * there strands you. What is left unsaid is the machinery: the reflection the End is built on, how
 * wormholes are paired, and where the gateways are. Knowing the rules is not the same as being
 * handed the map.
 *
 * <p>Every claim is read from the live configuration rather than written into the prose, so a
 * server that turns a mechanic off stops telling players it is on.
 *
 * <p>The text is deliberately styled as Bukkit's own help: gold labels, white body, and the same
 * header the help command draws. This is the vanilla help system with the server added to it as a
 * subject, not a branded overlay sitting on top of it.
 */
public class ServerHelp {

    private final PluginConfig config;
    private final List<StaticHelpTopic> topics;

    public ServerHelp(PluginConfig config) {
        this.config = config;
        // Names are fixed here, at construction, because the help map files a topic under its name
        // and will not accept a replacement later. Their contents are not: every body below is
        // rebuilt from config each time somebody reads it.
        this.topics = List.of(
            new StaticHelpTopic(config.getPresentation().getName(),
                "What this server is, and how it is governed.", this::server),
            new StaticHelpTopic("Rules",
                "The few things administration enforces.", this::rules),
            new StaticHelpTopic("Death",
                "What dying costs you.", this::death),
            // Short texts are kept short enough that "Name: text" clears 55 columns, because the
            // help index runs them through the same paginator the topics go through and a wrapped
            // index line reads as a second entry.
            new StaticHelpTopic("Respawn",
                "Where you wake up, and why it is elsewhere.", this::respawn),
            new StaticHelpTopic("Paths",
                "Keeping more than one existence.", this::paths),
            new StaticHelpTopic("Chat",
                "Who can hear you, and who can hear them.", this::chat),
            // Named for the dimension rather than for the block that changed: a player wondering
            // about the End types /help end, and Bukkit's fuzzy fallback only reaches topics
            // starting with the same letter, so a cleverer name would simply not be found.
            new StaticHelpTopic("End",
                "How travel to and from the End works.", this::end),
            new StaticHelpTopic("Strongholds",
                "Where eyes of ender lead.", this::strongholds)
        );
    }

    /**
     * Files the topics with the server's help map.
     *
     * <p>Must run while the plugin is enabling. The help map is emptied before plugins load and its
     * index is built after they have, so topics added now are both kept and listed; topics added
     * later would exist but never appear in {@code /help}.
     */
    public void register(HelpMap helpMap) {
        for (StaticHelpTopic topic : topics) {
            helpMap.addTopic(topic);
        }
    }

    /** The topic names, in the order the landing page lists them. */
    public List<String> topicNames() {
        return topics.stream().map(StaticHelpTopic::getName).toList();
    }

    /**
     * The page a bare {@code /help} opens: what the server is, what can be looked up, and how to
     * reach the command index that would otherwise have been here.
     */
    public void sendLandingPage(CommandSender sender) {
        PresentationConfig presentation = config.getPresentation();

        sender.sendMessage(header(presentation.getName()));
        if (presentation.hasTagline()) {
            sender.sendMessage(ChatColor.WHITE + presentation.getTagline());
        }
        // Broken by hand rather than by sentence length: this is the one claim on the page that has
        // to survive being read in a two-line chat box.
        sender.sendMessage(ChatColor.GRAY + "Administration governs the integrity of the server.");
        sender.sendMessage(ChatColor.GRAY + "Players govern the world.");

        for (StaticHelpTopic topic : topics) {
            // Bukkit's own index line, so this reads as part of the help system rather than as an
            // advertisement that happens to be printed by it.
            sender.sendMessage(ChatColor.GOLD + topic.getName() + ": "
                + ChatColor.WHITE + topic.getShortText());
        }

        // The way out. Without this line the landing page would have taken /help away from anyone
        // who typed it wanting the commands.
        sender.sendMessage(ChatColor.GRAY + "Use /help <topic>, or /help 1 for the command list.");
    }

    // ---------------------------------------------------------------- topic bodies

    private String server() {
        return description("The world is permanent. You are not.")
            + para("You woke far from origin, and far from anyone else. Death ends that life;"
                + " another begins elsewhere.")
            + para("The world does not reset. What every life before yours built or abandoned is"
                + " still where it was left.")
            + aside("What is and is not enforced here: /help rules.");
    }

    private String rules() {
        return description("Players govern the world.")
            + para("Kill, steal, raid, trap, betray, take ground and lose it again. No"
                + " administrator will undo it.")
            + para("Administration governs only the server itself:")
            + line("- No crashing it, lag machines, unauthorised access, hostile bots or ban"
                + " evasion.")
            + line("- No conduct outside Minecraft's own rules and Microsoft's Community"
                + " Standards.")
            + line("- No deliberate spam or flooding.")
            + aside("Technical Minecraft is welcome. It is only an exploit here if it attacks the"
                + " server rather than the game.");
    }

    private String death() {
        String enderChest = config.isDropEnderChestOnDeath()
            ? " So does your ender chest: it is not a vault here, and death leaves it empty."
            : " Your ender chest is untouched, and is the one thing that carries into the"
                + " next life.";

        return description("Death ends a life. It does not end you.")
            + para("Inventory and experience drop where you fell, as in vanilla." + enderChest)
            + para("What you leave behind stays where it fell, a long way from wherever you"
                + " wake up.");
    }

    private String respawn() {
        return description("You do not respawn. You begin again.")
            + para("There is no world spawn, and no bed will bring you back. Beds and anchors do"
                + " not set where you wake.")
            + para(newLifeDistances())
            + aside("Your base is not gone. It is only a long way away.");
    }

    /**
     * The band a new life is placed in, stated in the numbers actually configured.
     *
     * <p>Written from the settings rather than from prose because the distance <em>is</em> the
     * mechanic: a player told "far away" has been told nothing, and a player told a number that the
     * server no longer uses has been told something false. Each distance drops out of the sentence
     * when it is turned off.
     */
    private String newLifeDistances() {
        int fromDeath = config.getDeathRespawnMinDistanceFromDeath();
        int fromOrigin = config.getSpawnMinDistanceFromZero();

        if (fromDeath > 0 && fromOrigin > 0) {
            return "A new life begins at least " + blocks(fromDeath) + " from the one before it,"
                + " and never within " + count(fromOrigin) + " of origin.";
        }
        if (fromDeath > 0) {
            return "A new life begins at least " + blocks(fromDeath) + " from the one before it.";
        }
        if (fromOrigin > 0) {
            return "A new life begins somewhere new, never within " + blocks(fromOrigin)
                + " of origin.";
        }
        return "A new life begins somewhere new.";
    }

    /**
     * Paths, and the one thing about them a player will get wrong if nobody says it.
     *
     * <p>They will assume dying costs them a path. It is the natural reading on a server whose whole
     * premise is that death takes everything, and it is wrong in the direction that makes people
     * play worse: somebody who believes a death burns one of five existences will hoard them and
     * never begin one. So that sentence is first, and the cost of abandoning one — which is real —
     * is stated immediately after it so the two are never confused.
     */
    private String paths() {
        PathConfig paths = config.getPaths();
        if (!paths.isEnabled()) {
            return description("You have one existence in this world.")
                + para("Death ends the life; you begin another, elsewhere. See /help respawn.");
        }

        return description("You may keep up to " + paths.getMaxPaths()
                + " separate existences, and live one at a time.")
            + para("Each has its own place in the world, its own inventory, its own ender chest and"
                + " its own experience. Nothing passes between them. /path lists yours; your first"
                + " is called " + paths.getDefaultPathName() + ".")
            + para("Dying costs you nothing here. The life ends and the path receives another,"
                + " under the same name, a long way from where you fell.")
            + para("/path switch <name> leaves one and takes up another. /path new <name> begins"
                + " one, and naming other players asks them to begin theirs alongside yours, in the"
                + " same place — nobody is moved until all of you agree.")
            + aside("/path abandon <name> destroys one for good. Everything it was carrying falls"
                + " where it stood, for anybody at all to find. That is the only way to free a slot.");
    }

    /**
     * The other topic a player is measurably worse off for not having read.
     *
     * <p>A player who does not know chat is local reads an empty chat box as an empty server, and
     * shouting into it is the first thing anybody does. So the radius is stated as a number, the way
     * out of it is named, and what a contact does and does not carry is said plainly — because the
     * question a player will actually have about agreeing to one is whether it can be used to find
     * them.
     */
    private String chat() {
        SocialConfig social = config.getSocial();
        if (!social.isEnabled()) {
            return description("Everyone on the server hears everyone else.")
                + para("Chat, deaths and advancements are announced to the whole server, as they"
                    + " are in vanilla.");
        }

        String body = description("Chat is local. The world is not small.")
            + para("What you say reaches players within " + count(social.getChatRadius())
                + " blocks of you, in the same world. So do your deaths, your advancements, and your"
                + " coming and going. Everyone else hears nothing.")
            + para("/msg <player> <message> reaches anybody on the server, at any distance.");

        if (social.isContactsEnabled()) {
            body += para("A contact is someone who hears all of it wherever either of you is."
                + " /contact add <player> asks; they have to accept. Either of you can end it with"
                + " /contact remove, and it survives logging out, dying and being exiled.")
                + aside("A contact is hearing, not finding. It carries no coordinates, no tracking,"
                    + " and no way to reach you.");
            if (social.isAutoContactsEnabled()) {
                body += aside("Contacts also form by themselves with people you spend "
                    + (social.getAutoRequiredSeconds() / 60) + " minutes or so beside — not"
                    + " necessarily in one stretch, and the further apart you are the slower it"
                    + " counts. Both of you have to allow it, and time apart undoes it."
                    + " /contacts auto off if you would rather it did not.");
            }
        }

        return body + aside("/ignore <player> switches somebody off completely, whatever else is"
            + " true. They are never told.");
    }

    /**
     * The one topic a player is measurably worse off for not having read.
     *
     * <p>Both facts here are ones the End punishes you for guessing wrong about. A player who
     * expects to land at End 0,0 has planned a meeting that will not happen; a player who steps into
     * the first gateway they find expecting the outer islands, or expecting to get home, is thrown
     * an unknown distance into a dimension with no food in it.
     *
     * <p>So it states what each kind of door does and stops. The reflection rule, the shape of the
     * pairing and the grid that scatters the gateways are all still there to be worked out.
     */
    private String end() {
        if (!config.getDimensionalTravel().isEnabled()) {
            return description("The End behaves exactly as it does in vanilla.")
                + para("Every End portal arrives at the central island, and the gateways there lead"
                    + " to the outer islands, as they always have.");
        }

        return description("The End is not one shared island.")
            + para("Your portal takes you to a part of the End of its own, not to End 0,0. You"
                + " arrive on a platform whose gateway leads back to it, and that is the only way"
                + " out.")
            + para("Every other gateway is a wormhole: it throws you somewhere far off in the End,"
                + " and the one waiting there brings you back. None reach the Overworld.");
    }

    private String strongholds() {
        return description("Strongholds are not gathered near origin.")
            + para("Vanilla arranges them in rings around 0,0. On a map where nobody lives near"
                + " 0,0, that would make reaching the End a pilgrimage to the same empty place.")
            + para("They are scattered across the whole world instead. An eye of ender thrown where"
                + " you live points at a stronghold near where you live.");
    }

    // ---------------------------------------------------------------- formatting

    /**
     * The first line of every Bukkit help topic, in Bukkit's colours.
     *
     * <p>Keep {@code "Description: " + text} under 55 visible characters. At exactly 55 the word
     * wrapper takes its "line is already full" branch, closes the line and opens an empty one, and
     * the blank that follows lands on top of it — so a description that happens to measure the page
     * width renders with a double gap under it and pushes the topic onto a second page.
     */
    private static String description(String text) {
        return ChatColor.GOLD + "Description: " + ChatColor.WHITE + text;
    }

    /**
     * A paragraph: a blank line, then the text.
     *
     * <p>Wrapping is left to the help command, which breaks at the page width and carries each
     * line's colour on to the next — so a paragraph only has to name its colour once, at the start.
     */
    private static String para(String text) {
        return "\n\n" + ChatColor.WHITE + text;
    }

    /** A paragraph in the muted colour Bukkit uses for remarks rather than facts. */
    private static String aside(String text) {
        return "\n\n" + ChatColor.GRAY + text;
    }

    /** A line directly beneath the one before it, for lists. */
    private static String line(String text) {
        return "\n" + ChatColor.WHITE + text;
    }

    private static String blocks(int distance) {
        return count(distance) + " blocks";
    }

    /** A distance with the unit left to the sentence around it, where repeating it would not fit. */
    private static String count(int distance) {
        return NumberFormat.getIntegerInstance(Locale.ROOT).format(distance);
    }

    /**
     * Bukkit's help header, rebuilt exactly — including its habit of counting the colour codes
     * towards the width, which is why the rule of dashes falls short of the page. Matching the quirk
     * is the point: the landing page has to be indistinguishable from a page the help command drew.
     */
    private static String header(String title) {
        StringBuilder header = new StringBuilder()
            .append(ChatColor.YELLOW).append("--------- ")
            .append(ChatColor.WHITE).append("Help: ").append(title).append(' ')
            .append(ChatColor.YELLOW);
        while (header.length() < ChatPaginator.GUARANTEED_NO_WRAP_CHAT_PAGE_WIDTH) {
            header.append('-');
        }
        return header.toString();
    }
}
