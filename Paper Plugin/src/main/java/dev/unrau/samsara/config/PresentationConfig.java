package dev.unrau.samsara.config;

import org.bukkit.configuration.ConfigurationSection;

import java.util.logging.Logger;

/**
 * What the server calls itself, and what it is willing to tell a player who asks.
 *
 * <p>Nothing in here changes a rule. It changes only what players are <em>told</em> about rules
 * that already exist, which is why it is kept apart from every other section: the server can be
 * renamed, or go completely silent, without a single mechanic noticing.
 *
 * <p>All of it is spent on {@code /help}. The server list entry is not this plugin's business — it
 * comes from server.properties, as it does on any other server.
 */
public class PresentationConfig {

    /** The server's name. Not a description of the game mode — see the tagline for that. */
    private static final String DEFAULT_NAME = "Samsara";

    /** The line under the name on the help page. Enough to say what the server is, and no more. */
    private static final String DEFAULT_TAGLINE = "The world remembers. You do not.";

    private final String name;
    private final String tagline;
    private final boolean helpTopics;
    private final boolean helpLandingPage;

    private PresentationConfig(String name, String tagline, boolean helpTopics,
                               boolean helpLandingPage) {
        this.name = name;
        this.tagline = tagline;
        this.helpTopics = helpTopics;
        this.helpLandingPage = helpLandingPage;
    }

    /**
     * Reads the {@code presentation} section. A missing section yields the defaults with everything
     * on, so a server that upgrades the jar is named and documented without editing config.yml.
     */
    public static PresentationConfig from(ConfigurationSection section, Logger logger) {
        if (section == null) {
            return new PresentationConfig(DEFAULT_NAME, DEFAULT_TAGLINE, true, true);
        }

        return new PresentationConfig(
            readName(section, logger),
            // A blank tagline is a legitimate choice — it asks for a help page that opens on the
            // name alone — so unlike the name it is taken as written.
            section.getString("tagline", DEFAULT_TAGLINE).trim(),
            section.getBoolean("help.topics", true),
            section.getBoolean("help.landingPage", true)
        );
    }

    /**
     * The server's name, never blank.
     *
     * <p>A blank name is not a quieter server, it is a broken one: the help page loses its title and
     * the topic named after the server becomes unaddressable. Turn the features off instead of
     * emptying the name.
     */
    private static String readName(ConfigurationSection section, Logger logger) {
        String configured = section.getString("name", DEFAULT_NAME);
        if (configured == null || configured.isBlank()) {
            logger.warning("presentation.name is blank; using '" + DEFAULT_NAME + "'. To stop the"
                + " server naming itself, turn off presentation.help rather than emptying it.");
            return DEFAULT_NAME;
        }
        return configured.trim();
    }

    public String getName()             { return name; }
    public String getTagline()          { return tagline; }
    public boolean hasTagline()         { return !tagline.isEmpty(); }
    public boolean isHelpTopics()       { return helpTopics; }
    public boolean isHelpLandingPage()  { return helpLandingPage; }
}
