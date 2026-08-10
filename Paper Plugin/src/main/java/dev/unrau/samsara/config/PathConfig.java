package dev.unrau.samsara.config;

import dev.unrau.samsara.path.PathNames;
import org.bukkit.configuration.ConfigurationSection;

import java.util.logging.Logger;

/**
 * How many existences one account may keep, and what the world is told when somebody steps between
 * them.
 *
 * <p>The limit is the whole design. A player who can hold five paths has a real choice to make
 * about beginning a sixth; a player who can hold fifty has a stable of characters and no stake in
 * any of them. It is configurable because a server may disagree about the number — not about
 * whether there is one.
 *
 * <p>The messages exist because vanilla's "joined the game" is describing something that no longer
 * happens here. What arrives is one of several existences belonging to one person, and what leaves
 * is the same. Every line is a template with {@code %player%} and {@code %path%} in it, and an empty
 * line means say nothing at all — a server that wants silence has somewhere to ask for it.
 */
public class PathConfig {

    /**
     * Furthest the limit may be configured. Not a technical bound — each path is one small file —
     * but past this the word "path" has stopped meaning anything and the tab completer is a wall.
     */
    private static final int MAX_PATHS_CEILING = 20;

    private static final String DEFAULT_PATH_NAME = "Original";

    private final boolean enabled;
    private final int maxPaths;
    private final String defaultPathName;
    private final int invitationTimeoutSeconds;

    private final String joinMessage;
    private final String quitMessage;
    private final String departureMessage;
    private final String arrivalMessage;

    private PathConfig(boolean enabled, int maxPaths, String defaultPathName,
                       int invitationTimeoutSeconds, String joinMessage, String quitMessage,
                       String departureMessage, String arrivalMessage) {
        this.enabled = enabled;
        this.maxPaths = maxPaths;
        this.defaultPathName = defaultPathName;
        this.invitationTimeoutSeconds = invitationTimeoutSeconds;
        this.joinMessage = joinMessage;
        this.quitMessage = quitMessage;
        this.departureMessage = departureMessage;
        this.arrivalMessage = arrivalMessage;
    }

    /**
     * Reads the {@code paths} section. A missing section yields the shipped defaults with the
     * feature on, so a server that upgrades the jar gets paths without editing config.yml — and
     * gets them harmlessly, because every existing player is simply walking their first one.
     */
    public static PathConfig from(ConfigurationSection section, Logger logger) {
        if (section == null) {
            return defaults(true);
        }

        return new PathConfig(
            section.getBoolean("enabled", true),
            readMax(section, logger),
            readDefaultName(section, logger),
            bounded(section, "invitationTimeoutSeconds", 120, 10, 600, logger),
            // Taken exactly as written, blanks included: an empty template is a server asking for
            // that announcement not to be made, which is a real answer.
            section.getString("messages.join", defaults(true).joinMessage),
            section.getString("messages.quit", defaults(true).quitMessage),
            section.getString("messages.departure", defaults(true).departureMessage),
            section.getString("messages.arrival", defaults(true).arrivalMessage)
        );
    }

    /**
     * The shipped wording.
     *
     * <p>Join and quit are empty on purpose, which hands those two back to vanilla. Connecting and
     * disconnecting are the two announcements the game already makes, already words correctly, and
     * — the part that cannot be got back any other way — already translates into whatever language
     * each client is set to. Overwriting them with a template would trade every player's own
     * language for a turn of phrase, and it would do it on servers that never asked.
     *
     * <p>Departure and arrival keep their wording, because there is nothing to defer to: no part of
     * the game announces a player becoming somebody else, so the choice there is these words or
     * silence.
     */
    private static PathConfig defaults(boolean enabled) {
        return new PathConfig(enabled, 5, DEFAULT_PATH_NAME, 120,
            "",
            "",
            "%player%'s incarnation here ends.",
            "%player% enters an incarnation.");
    }

    /**
     * Reads the limit, refusing values that would make it meaningless.
     *
     * <p>Zero and below are the interesting case: they read as "no paths at all", which the rest of
     * the plugin cannot express — every player has at least the one they are standing in. A server
     * that wants exactly that writes {@code enabled: false}, and is told so.
     */
    private static int readMax(ConfigurationSection section, Logger logger) {
        int fallback = defaults(true).maxPaths;
        if (!section.isSet("max")) return fallback;

        int value = section.getInt("max", fallback);
        if (value < 1) {
            logger.warning("[Samsara] 'paths.max' is " + value + ", which would leave players with"
                + " nowhere to exist; using " + fallback + ". To switch paths off entirely, set"
                + " 'paths.enabled: false' — everybody keeps the one path they are walking.");
            return fallback;
        }
        if (value > MAX_PATHS_CEILING) {
            logger.warning("[Samsara] 'paths.max' is " + value + "; clamping to "
                + MAX_PATHS_CEILING + ".");
            return MAX_PATHS_CEILING;
        }
        return value;
    }

    /**
     * What a player's first path is called before they have renamed it.
     *
     * <p>A blank name is not a quieter default, it is an unaddressable path: every command takes a
     * name, and a path with none could never be switched to, renamed or abandoned.
     */
    private static String readDefaultName(ConfigurationSection section, Logger logger) {
        String configured = section.getString("defaultName", DEFAULT_PATH_NAME);
        String rejection = configured == null ? "it is missing" : PathNames.rejectionFor(configured);
        if (rejection != null) {
            logger.warning("[Samsara] 'paths.defaultName' cannot be used because " + rejection
                + "; using '" + DEFAULT_PATH_NAME + "'.");
            return DEFAULT_PATH_NAME;
        }
        return configured.trim();
    }

    private static int bounded(ConfigurationSection section, String key, int fallback,
                               int min, int max, Logger logger) {
        int value = section.getInt(key, fallback);
        if (value < min || value > max) {
            logger.warning("[Samsara] 'paths." + key + "' must be between " + min + " and " + max
                + " (got " + value + "); using " + fallback + ".");
            return fallback;
        }
        return value;
    }

    public boolean isEnabled()               { return enabled; }
    public int getMaxPaths()                 { return maxPaths; }
    public String getDefaultPathName()       { return defaultPathName; }
    public int getInvitationTimeoutSeconds() { return invitationTimeoutSeconds; }
    public String getJoinMessage()           { return joinMessage; }
    public String getQuitMessage()           { return quitMessage; }
    public String getDepartureMessage()      { return departureMessage; }
    public String getArrivalMessage()        { return arrivalMessage; }
}
