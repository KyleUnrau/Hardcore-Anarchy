package dev.unrau.samsara.config;

import org.bukkit.configuration.ConfigurationSection;

import java.util.logging.Logger;

/**
 * Who is allowed to see whom.
 *
 * <p>Vanilla treats the player list as one room: everybody hears everybody, wherever they are. On a
 * map where lives begin hundreds of thousands of blocks apart that is the loudest possible lie about
 * the world — the chat box is the one place the distance does not exist. These settings put the
 * distance back into it.
 *
 * <p>Two things reach across the map, and only two: a <em>contact</em>, which both players agreed
 * to, and a private message, which is addressed. Everything else is local.
 *
 * <p>Values are clamped on load, with a warning, in the same way as {@link DimensionalTravelConfig}
 * and for the same reason: a typo should cost the feature its tuning, not cost a player their chat.
 */
public class SocialConfig {

    /**
     * Furthest a social radius may be configured to reach, in blocks.
     *
     * <p>The world's own limit, so a server that wants vanilla's one shared room can ask for it by
     * writing a radius nothing can be outside of, rather than by removing the plugin.
     */
    private static final int MAX_RADIUS = 30_000_000;

    private final boolean enabled;

    private final int chatRadius;
    private final int joinRadius;
    private final int quitRadius;
    private final int deathRadius;
    private final int advancementRadius;

    private final boolean contactsEnabled;
    private final int maxContacts;
    private final int requestExpirySeconds;
    private final int requestCooldownSeconds;

    private final boolean autoContactsEnabled;
    private final boolean autoContactsDefaultOn;
    private final int autoRadius;
    private final int autoCloseRadius;
    private final double autoDecayRate;
    private final int autoRequiredSeconds;
    private final int autoSampleIntervalTicks;
    private final int autoForgetAfterSeconds;

    private final boolean messagesEnabled;
    private final int messageMinIntervalMillis;
    private final int messageWindowSeconds;
    private final int maxUniqueRecipientsPerWindow;
    private final int maxDuplicateRecipients;
    private final int maxMessagesPerWindow;

    private final boolean petDeathsEnabled;
    private final int petRadius;
    private final boolean petTellOwner;

    private final int flushIntervalSeconds;

    private SocialConfig(Builder builder) {
        this.enabled = builder.enabled;
        this.chatRadius = builder.chatRadius;
        this.joinRadius = builder.joinRadius;
        this.quitRadius = builder.quitRadius;
        this.deathRadius = builder.deathRadius;
        this.advancementRadius = builder.advancementRadius;
        this.contactsEnabled = builder.contactsEnabled;
        this.maxContacts = builder.maxContacts;
        this.requestExpirySeconds = builder.requestExpirySeconds;
        this.requestCooldownSeconds = builder.requestCooldownSeconds;
        this.autoContactsEnabled = builder.autoContactsEnabled;
        this.autoContactsDefaultOn = builder.autoContactsDefaultOn;
        this.autoRadius = builder.autoRadius;
        this.autoCloseRadius = builder.autoCloseRadius;
        this.autoDecayRate = builder.autoDecayRate;
        this.autoRequiredSeconds = builder.autoRequiredSeconds;
        this.autoSampleIntervalTicks = builder.autoSampleIntervalTicks;
        this.autoForgetAfterSeconds = builder.autoForgetAfterSeconds;
        this.messagesEnabled = builder.messagesEnabled;
        this.messageMinIntervalMillis = builder.messageMinIntervalMillis;
        this.messageWindowSeconds = builder.messageWindowSeconds;
        this.maxUniqueRecipientsPerWindow = builder.maxUniqueRecipientsPerWindow;
        this.maxDuplicateRecipients = builder.maxDuplicateRecipients;
        this.maxMessagesPerWindow = builder.maxMessagesPerWindow;
        this.petDeathsEnabled = builder.petDeathsEnabled;
        this.petRadius = builder.petRadius;
        this.petTellOwner = builder.petTellOwner;
        this.flushIntervalSeconds = builder.flushIntervalSeconds;
    }

    /** Mutable carrier for the defaults, so reading the section stays one assignment per key. */
    private static final class Builder {
        boolean enabled = true;

        // Comfortably past any server's render distance, so you can hear the people whose chimneys
        // you cannot see, and nothing at all from the next valley. One key sets all five; the
        // per-event keys below exist for servers that want death to carry further than small talk.
        int radius = 256;
        int chatRadius = 256;
        int joinRadius = 256;
        int quitRadius = 256;
        int deathRadius = 256;
        int advancementRadius = 256;

        boolean contactsEnabled = true;
        int maxContacts = 100;
        int requestExpirySeconds = 300;
        int requestCooldownSeconds = 300;

        boolean autoContactsEnabled = true;
        // On, because a contact is hearing and not finding: what it costs the pair is that they stop
        // being hidden from each other by distance, which is the thing an evening spent working side
        // by side has already stopped being true. /contacts auto off is one command, and it is the
        // first thing the contacts help text says.
        boolean autoContactsDefaultOn = true;
        // Where credit stops entirely. Beyond this the two of them are not doing anything together.
        int autoRadius = 48;
        // Where credit is full. Inside this they are in the same room; between the two radii the
        // score is earned proportionally, so a player across the field builds one slowly and a
        // player at the far edge of the radius builds one barely at all.
        int autoCloseRadius = 16;
        // Score lost per second apart, as a fraction of the rate it is earned at touching distance.
        // A quarter: an evening together survives the next day comfortably, and an afternoon of
        // walking past each other at spawn is gone by the evening.
        double autoDecayRate = 0.25;
        int autoRequiredSeconds = 20 * 60;
        int autoSampleIntervalTicks = 100;
        // The backstop under the decay: progress nothing has touched for this long is dropped
        // outright, so a server that sets the decay rate to zero still does not keep a list of
        // everybody a player has ever walked past.
        int autoForgetAfterSeconds = 180 * 60;

        boolean messagesEnabled = true;
        int messageMinIntervalMillis = 200;
        int messageWindowSeconds = 60;
        int maxUniqueRecipientsPerWindow = 6;
        int maxDuplicateRecipients = 3;
        int maxMessagesPerWindow = 40;

        boolean petDeathsEnabled = true;
        int petRadius = 64;
        boolean petTellOwner = false;

        int flushIntervalSeconds = 300;
    }

    /**
     * Reads the {@code social} section. A missing section yields the defaults with the feature on,
     * so a server that upgrades the jar gets proximity chat without editing config.yml — and can
     * have vanilla's one shared room back with a single key.
     */
    public static SocialConfig from(ConfigurationSection section, Logger logger) {
        Builder builder = new Builder();
        if (section == null) {
            return new SocialConfig(builder);
        }

        builder.enabled = section.getBoolean("enabled", builder.enabled);

        // One radius, then the per-event overrides that default to it. Written this way round so a
        // server can retune the whole system with one number and never see the other five.
        builder.radius = bounded(section, "radius", builder.radius, 0, MAX_RADIUS, logger);
        builder.chatRadius = radius(section, "chat", builder.radius, logger);
        builder.joinRadius = radius(section, "join", builder.radius, logger);
        builder.quitRadius = radius(section, "quit", builder.radius, logger);
        builder.deathRadius = radius(section, "death", builder.radius, logger);
        builder.advancementRadius = radius(section, "advancement", builder.radius, logger);

        ConfigurationSection contacts = section.getConfigurationSection("contacts");
        if (contacts != null) {
            builder.contactsEnabled = contacts.getBoolean("enabled", builder.contactsEnabled);
            builder.maxContacts = bounded(contacts, "contacts.max", "max",
                builder.maxContacts, 1, 10_000, logger);
            builder.requestExpirySeconds = bounded(contacts, "contacts.requestExpirySeconds",
                "requestExpirySeconds", builder.requestExpirySeconds, 10, 86_400, logger);
            builder.requestCooldownSeconds = bounded(contacts, "contacts.requestCooldownSeconds",
                "requestCooldownSeconds", builder.requestCooldownSeconds, 0, 86_400, logger);

            ConfigurationSection auto = contacts.getConfigurationSection("auto");
            if (auto != null) {
                builder.autoContactsEnabled = auto.getBoolean("enabled", builder.autoContactsEnabled);
                builder.autoContactsDefaultOn = auto.getBoolean("defaultOn", builder.autoContactsDefaultOn);
                builder.autoRadius = bounded(auto, "contacts.auto.radius", "radius",
                    builder.autoRadius, 1, 512, logger);
                builder.autoCloseRadius = bounded(auto, "contacts.auto.closeRadius", "closeRadius",
                    builder.autoCloseRadius, 0, 512, logger);
                builder.autoDecayRate = bounded(auto, "contacts.auto.decayRate", "decayRate",
                    builder.autoDecayRate, 0, 100, logger);
                builder.autoRequiredSeconds = 60 * bounded(auto, "contacts.auto.requiredMinutes",
                    "requiredMinutes", builder.autoRequiredSeconds / 60, 1, 10_080, logger);
                builder.autoSampleIntervalTicks = bounded(auto, "contacts.auto.sampleIntervalTicks",
                    "sampleIntervalTicks", builder.autoSampleIntervalTicks, 20, 12_000, logger);
                builder.autoForgetAfterSeconds = 60 * bounded(auto, "contacts.auto.forgetAfterMinutes",
                    "forgetAfterMinutes", builder.autoForgetAfterSeconds / 60, 1, 525_600, logger);
            }
        }

        ConfigurationSection messages = section.getConfigurationSection("messages");
        if (messages != null) {
            builder.messagesEnabled = messages.getBoolean("enabled", builder.messagesEnabled);
            builder.messageMinIntervalMillis = bounded(messages, "messages.minIntervalMillis",
                "minIntervalMillis", builder.messageMinIntervalMillis, 0, 60_000, logger);
            builder.messageWindowSeconds = bounded(messages, "messages.windowSeconds",
                "windowSeconds", builder.messageWindowSeconds, 5, 3_600, logger);
            builder.maxUniqueRecipientsPerWindow = bounded(messages,
                "messages.maxUniqueRecipientsPerWindow", "maxUniqueRecipientsPerWindow",
                builder.maxUniqueRecipientsPerWindow, 1, 1_000, logger);
            builder.maxDuplicateRecipients = bounded(messages, "messages.maxDuplicateRecipients",
                "maxDuplicateRecipients", builder.maxDuplicateRecipients, 1, 1_000, logger);
            builder.maxMessagesPerWindow = bounded(messages, "messages.maxMessagesPerWindow",
                "maxMessagesPerWindow", builder.maxMessagesPerWindow, 1, 10_000, logger);
        }

        ConfigurationSection pets = section.getConfigurationSection("pets");
        if (pets != null) {
            builder.petDeathsEnabled = pets.getBoolean("enabled", builder.petDeathsEnabled);
            builder.petRadius = bounded(pets, "pets.radius", "radius",
                builder.petRadius, 0, MAX_RADIUS, logger);
            builder.petTellOwner = pets.getBoolean("tellOwner", builder.petTellOwner);
        }

        builder.flushIntervalSeconds = bounded(section, "saveIntervalSeconds",
            builder.flushIntervalSeconds, 30, 3_600, logger);

        if (builder.autoCloseRadius > builder.autoRadius) {
            // The two describe the same distance from opposite ends, and crossed over they would
            // describe nothing: everything inside the outer radius would earn full credit and the
            // taper would not exist. Fall back to the shape without a taper, which is at least the
            // behaviour the numbers were reaching for.
            logger.warning("social.contacts.auto.closeRadius (" + builder.autoCloseRadius + ") is"
                + " larger than social.contacts.auto.radius (" + builder.autoRadius + "); using "
                + builder.autoRadius + " for both, so nearness is all-or-nothing.");
            builder.autoCloseRadius = builder.autoRadius;
        }

        if (builder.autoRadius > builder.chatRadius) {
            // The learning radius is meant to be the smaller of the two: it describes people you are
            // doing something with, not everyone within earshot.
            logger.warning("social.contacts.auto.radius (" + builder.autoRadius + ") is larger than"
                + " the chat radius (" + builder.chatRadius + "); contacts will form between players"
                + " who have never been able to hear each other.");
        }

        return new SocialConfig(builder);
    }

    /** Reads one of the per-event radius overrides, defaulting to the single {@code radius} key. */
    private static int radius(ConfigurationSection section, String key, int fallback, Logger logger) {
        ConfigurationSection radii = section.getConfigurationSection("radii");
        if (radii == null) return fallback;
        return bounded(radii, "radii." + key, key, fallback, 0, MAX_RADIUS, logger);
    }

    private static int bounded(ConfigurationSection section, String key, int fallback,
                               int min, int max, Logger logger) {
        return bounded(section, key, key, fallback, min, max, logger);
    }

    /** Reads a key from a nested section, reporting the full path so the warning is actionable. */
    private static int bounded(ConfigurationSection section, String path, String key, int fallback,
                               int min, int max, Logger logger) {
        int value = section.getInt(key, fallback);
        if (value < min || value > max) {
            logger.warning("social." + path + " must be between " + min + " and " + max
                + " (got " + value + "); using " + fallback + ".");
            return fallback;
        }
        return value;
    }

    /** The same, for a key whose sensible values are fractions rather than whole blocks. */
    private static double bounded(ConfigurationSection section, String path, String key,
                                  double fallback, double min, double max, Logger logger) {
        double value = section.getDouble(key, fallback);
        if (!(value >= min) || !(value <= max)) {
            logger.warning("social." + path + " must be between " + min + " and " + max
                + " (got " + value + "); using " + fallback + ".");
            return fallback;
        }
        return value;
    }

    public boolean isEnabled()                    { return enabled; }
    public int getChatRadius()                    { return chatRadius; }
    public int getJoinRadius()                    { return joinRadius; }
    public int getQuitRadius()                    { return quitRadius; }
    public int getDeathRadius()                   { return deathRadius; }
    public int getAdvancementRadius()             { return advancementRadius; }
    public boolean isContactsEnabled()            { return contactsEnabled; }
    public int getMaxContacts()                   { return maxContacts; }
    public int getRequestExpirySeconds()          { return requestExpirySeconds; }
    public int getRequestCooldownSeconds()        { return requestCooldownSeconds; }
    public boolean isAutoContactsEnabled()        { return autoContactsEnabled; }
    public boolean isAutoContactsDefaultOn()      { return autoContactsDefaultOn; }
    public int getAutoRadius()                    { return autoRadius; }
    public int getAutoCloseRadius()               { return autoCloseRadius; }
    public double getAutoDecayRate()              { return autoDecayRate; }
    public int getAutoRequiredSeconds()           { return autoRequiredSeconds; }
    public int getAutoSampleIntervalTicks()       { return autoSampleIntervalTicks; }
    public int getAutoForgetAfterSeconds()        { return autoForgetAfterSeconds; }
    public boolean isMessagesEnabled()            { return messagesEnabled; }
    public int getMessageMinIntervalMillis()      { return messageMinIntervalMillis; }
    public int getMessageWindowSeconds()          { return messageWindowSeconds; }
    public int getMaxUniqueRecipientsPerWindow()  { return maxUniqueRecipientsPerWindow; }
    public int getMaxDuplicateRecipients()        { return maxDuplicateRecipients; }
    public int getMaxMessagesPerWindow()          { return maxMessagesPerWindow; }
    public boolean isPetDeathsEnabled()           { return petDeathsEnabled; }
    public int getPetRadius()                     { return petRadius; }
    public boolean isPetTellOwner()               { return petTellOwner; }
    public int getFlushIntervalSeconds()          { return flushIntervalSeconds; }
}
