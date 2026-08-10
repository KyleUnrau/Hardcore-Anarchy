package dev.unrau.samsara.path;

import dev.unrau.samsara.config.PathConfig;
import dev.unrau.samsara.config.PluginConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;

/**
 * What the server says when somebody arrives in an existence or steps out of one.
 *
 * <p>Vanilla's "Steve joined the game" is describing something that does not happen here. What
 * arrives is one of several existences belonging to one person, and the same words have to cover a
 * player who has just connected, a player who has just disconnected, and a player who has done
 * neither and simply gone to live somewhere else as somebody else. Saying "joined the game" for the
 * third one would be a lie, and saying nothing would make a person appear out of the air.
 *
 * <p>Every line is a template read from the configuration at the moment it is needed, so
 * {@code /samsara reload} changes the wording on a running server. An empty template says nothing at
 * all, which is how a server asks for silence.
 *
 * <p>What is lost by replacing vanilla's own components with these is worth stating: vanilla builds
 * its join and leave messages as translatable components, and a client reading them in French gets
 * them in French. A configured string is a string, in whatever language the operator wrote it. That
 * is the cost of the feature, it is not recoverable, and a server that would rather keep the
 * translation empties these templates and keeps vanilla's.
 */
public class PathMessages {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final PluginConfig config;

    public PathMessages(PluginConfig config) {
        this.config = config;
    }

    /**
     * What to announce when a player connects.
     *
     * @param vanilla the message the server built, which is returned untouched when this feature is
     *                off — and honoured when it is null, because another plugin silencing a join is
     *                a decision this one does not overrule
     */
    public Component join(Player player, String pathName, Component vanilla) {
        return replace(vanilla, settings().getJoinMessage(), player, pathName);
    }

    /** What to announce when a player disconnects. */
    public Component quit(Player player, String pathName, Component vanilla) {
        return replace(vanilla, settings().getQuitMessage(), player, pathName);
    }

    /**
     * What to announce where a player was standing when they left that existence behind.
     *
     * <p>Not a disconnection and not a death: to everyone watching, somebody who was there is not
     * there any more, and that is exactly what the words have to carry.
     */
    public Component departure(Player player, String pathName) {
        return render(settings().getDepartureMessage(), player, pathName);
    }

    /** What to announce where a player has just appeared as somebody else. */
    public Component arrival(Player player, String pathName) {
        return render(settings().getArrivalMessage(), player, pathName);
    }

    /** Whether these messages are in force at all. */
    public boolean isEnabled() {
        return settings().isEnabled();
    }

    private PathConfig settings() {
        return config.getPaths();
    }

    /**
     * The path wording for an announcement vanilla would have made, or vanilla's own.
     *
     * <p>A blank template hands the message back untouched rather than silencing it, which is the
     * escape hatch for the one thing this feature costs: vanilla's join and leave messages are
     * translatable and these are not, so a server that would rather keep every client reading its
     * own language empties these two lines and keeps everything else about paths.
     *
     * <p>{@link #departure} and {@link #arrival} treat a blank template as silence instead. There is
     * no vanilla message to fall back to there — nothing in the game announces a player becoming
     * somebody else — so the only two answers are these words or none.
     */
    private Component replace(Component vanilla, String template, Player player, String pathName) {
        if (!settings().isEnabled()) return vanilla;
        if (template == null || template.isBlank()) return vanilla;
        // A join or leave that something else has already silenced stays silent. This owns the
        // wording of an announcement, not the question of whether one is made.
        if (vanilla == null) return null;
        return render(template, player, pathName);
    }

    private Component render(String template, Player player, String pathName) {
        if (template == null || template.isBlank()) return null;

        String text = template
            .replace("%player%", player.getName())
            .replace("%path%", pathName == null ? "" : pathName);
        return LEGACY.deserialize(text);
    }
}
