package dev.unrau.samsara.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

public class PluginConfig {

    /** Vanilla's hard coordinate limit; no configured distance can usefully exceed it. */
    private static final int MAX_DISTANCE = 29_999_984;

    /**
     * The round number written for "the whole map".
     *
     * <p>It sits 16 blocks past {@link #MAX_DISTANCE}, so it is clamped like any other oversized
     * value — but silently, because it is not a mistake: it is what the world limit is called
     * everywhere outside the source of Minecraft, and an operator who writes it has said exactly
     * what they meant. Anything beyond it is a typo worth a warning.
     */
    private static final int NOMINAL_WORLD_LIMIT = 30_000_000;

    /**
     * How far out new lives are placed when nothing says otherwise, in blocks.
     *
     * <p>Just inside the world limit rather than exactly on it. The last million blocks of the map
     * are where the terrain generator's own arithmetic starts to fray, and a life that begins out
     * there begins somewhere subtly wrong; a million blocks of margin costs a square band nobody
     * will ever notice is missing and buys every new life ordinary ground to stand on.
     */
    private static final int DEFAULT_MAX_DISTANCE = 29_000_000;

    /**
     * Furthest a sound is allowed to be configured to carry, in blocks.
     *
     * <p>Well past any server's view distance: a player who cannot see the stronghold's chunks is
     * not being sent its sounds either, whatever this says.
     */
    private static final int MAX_SOUND_RADIUS = 512;

    private final JavaPlugin plugin;

    private String worldName;
    private SpawnAreaShape spawnAreaShape;
    private boolean respectWorldBorder;
    private int spawnMinDistanceFromZero;
    private int spawnMaxDistanceFromZero;
    private int deathRespawnMinDistanceFromDeath;
    private boolean avoidOcean;
    private boolean avoidLava;
    private int maxSafeSpawnAttempts;
    private boolean journalEnabled;
    private int journalMaxEntries;
    private boolean dropEnderChestOnDeath;
    private int endPortalActivationSoundRadius;
    private boolean arrivalPrepareBeforeJoin;
    private int arrivalPreparationTimeoutSeconds;
    private int arrivalPreloadRadius;

    private DimensionalTravelConfig dimensionalTravel;
    private PresentationConfig presentation;
    private SocialConfig social;
    private PathConfig paths;

    public PluginConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.reloadConfig();
        var cfg = plugin.getConfig();

        worldName                       = nonBlank(cfg.getString("worldName"), "world", "worldName");
        spawnAreaShape                  = readShape(cfg.getString("spawnAreaShape"));
        respectWorldBorder              = cfg.getBoolean("respectWorldBorder", true);
        // These two band every new life, first join as much as death respawn, which is why they no
        // longer carry 'deathRespawn' in their names. The old names are read as a fallback so an
        // existing server keeps its tuning across the rename.
        spawnMinDistanceFromZero        = readDistance(cfg, "spawnMinDistanceFromZero",
                                          "deathRespawnMinDistanceFromZero", 0);
        spawnMaxDistanceFromZero        = readDistance(cfg, "spawnMaxDistanceFromZero",
                                          "deathRespawnMaxDistanceFromZero", DEFAULT_MAX_DISTANCE);
        deathRespawnMinDistanceFromDeath= readDistance(cfg, "deathRespawnMinDistanceFromDeath", 500000);
        avoidOcean                      = cfg.getBoolean("avoidOcean", true);
        avoidLava                       = cfg.getBoolean("avoidLava", true);
        maxSafeSpawnAttempts            = Math.max(1, cfg.getInt("maxSafeSpawnAttempts", 64));
        // 'logSpawnLocations' is what this switch was called while the history went to a single
        // exile-log.csv. It is read as a fallback so a server that turned logging off keeps it off
        // after the upgrade, rather than quietly starting to write journals again.
        journalEnabled                  = cfg.getBoolean("journal.enabled",
                                          cfg.getBoolean("logSpawnLocations", true));
        journalMaxEntries               = readEntryLimit(cfg, "journal.maxEntries", 200);
        // Dropping and clearing used to be two independent keys, which allowed nonsense combinations
        // (clear without dropping deletes items; drop without clearing duplicates them). The old
        // 'clearEnderChestOnDeath' key is still honoured as a fallback so upgrading the jar does not
        // silently change behaviour on a server that set it to false.
        dropEnderChestOnDeath           = cfg.getBoolean("dropEnderChestOnDeath",
                                          cfg.getBoolean("clearEnderChestOnDeath", true));
        endPortalActivationSoundRadius  = readSoundRadius(cfg, "endPortal.activationSoundRadius", 64);
        arrivalPrepareBeforeJoin        = cfg.getBoolean("arrival.prepareBeforeJoin", true);
        arrivalPreparationTimeoutSeconds= bounded(cfg, "arrival.timeoutSeconds", 20, 1, 60);
        arrivalPreloadRadius            = bounded(cfg, "arrival.preloadRadius", 48, 0, 256);

        // 'endTravel' is an earlier name for this section. Reading it as a fallback means a server
        // that upgrades the jar keeps its tuning without editing anything.
        ConfigurationSection travelSection = cfg.getConfigurationSection("dimensionalTravel");
        if (travelSection == null) {
            travelSection = cfg.getConfigurationSection("endTravel");
            if (travelSection != null) {
                plugin.getLogger().info("[Samsara] Reading dimensional travel settings from the"
                    + " legacy 'endTravel' section. Rename it to 'dimensionalTravel' when convenient.");
            }
        }
        dimensionalTravel = DimensionalTravelConfig.from(travelSection, worldName, plugin.getLogger());

        presentation = PresentationConfig.from(cfg.getConfigurationSection("presentation"),
            plugin.getLogger());

        social = SocialConfig.from(cfg.getConfigurationSection("social"), plugin.getLogger());

        paths = PathConfig.from(cfg.getConfigurationSection("paths"), plugin.getLogger());
    }

    private String nonBlank(String value, String fallback, String key) {
        if (value == null || value.isBlank()) {
            plugin.getLogger().warning("[Samsara] '" + key + "' is missing or blank; using '" + fallback + "'.");
            return fallback;
        }
        return value.trim();
    }

    /**
     * Reads the shape of the spawn band, defaulting to the one that uses the whole map.
     *
     * <p>Square is the default because the world is square. A circle of the world's radius leaves
     * the four corners — most of the map, by area — permanently unreachable by exile, and there is
     * nothing out there that deserves to be off limits.
     */
    private SpawnAreaShape readShape(String raw) {
        SpawnAreaShape parsed = SpawnAreaShape.parse(raw);
        if (parsed == null) {
            if (raw != null && !raw.isBlank()) {
                plugin.getLogger().warning("[Samsara] Unrecognised spawnAreaShape '" + raw
                    + "'; expected 'circle' or 'square'. Using square.");
            }
            return SpawnAreaShape.SQUARE;
        }
        return parsed;
    }

    /**
     * Reads a block distance that used to be called something else, preferring the current name and
     * falling back to the old one. A server that upgrades the jar keeps its tuning without editing
     * anything; a server that has already renamed the key is never second-guessed by a stale value
     * left further down the file.
     */
    private int readDistance(ConfigurationSection cfg, String key, String legacyKey, int fallback) {
        if (!cfg.isSet(key) && cfg.isSet(legacyKey)) {
            plugin.getLogger().info("[Samsara] '" + legacyKey + "' has been renamed to '" + key
                + "'; it applies to first join as well as death respawn. Reading the old key —"
                + " rename it when convenient.");
            return readDistance(cfg, legacyKey, fallback);
        }
        return readDistance(cfg, key, fallback);
    }

    /**
     * Reads a block distance, tolerating values that are missing, non-numeric, negative or larger
     * than the world itself. A bad number here must never be the reason a player fails to be exiled,
     * so every case resolves to a usable distance instead of throwing.
     */
    private int readDistance(ConfigurationSection cfg, String key, int fallback) {
        if (!cfg.isSet(key)) return fallback;

        Object raw = cfg.get(key);
        if (!(raw instanceof Number number)) {
            plugin.getLogger().warning("[Samsara] '" + key + "' is not a number (found '" + raw
                + "'); using " + fallback + ".");
            return fallback;
        }

        long value = number.longValue();
        if (value < 0) {
            plugin.getLogger().warning("[Samsara] '" + key + "' is negative (" + value + "); using 0.");
            return 0;
        }
        if (value > MAX_DISTANCE) {
            if (value > NOMINAL_WORLD_LIMIT) {
                plugin.getLogger().warning("[Samsara] '" + key + "' is " + value
                    + ", beyond the world limit; clamping to " + MAX_DISTANCE + ".");
            }
            return MAX_DISTANCE;
        }
        return (int) value;
    }

    /**
     * Reads how many journal entries a player keeps.
     *
     * <p>Zero means every entry is kept, which is a real answer rather than a mistake: a server
     * that wants a complete history says so with a zero. A negative number is the same intent
     * expressed badly, so it is read as zero too.
     */
    private int readEntryLimit(ConfigurationSection cfg, String key, int fallback) {
        if (!cfg.isSet(key)) return fallback;

        Object raw = cfg.get(key);
        if (!(raw instanceof Number number)) {
            plugin.getLogger().warning("[Samsara] '" + key + "' is not a number (found '" + raw
                + "'); using " + fallback + ".");
            return fallback;
        }

        long value = number.longValue();
        if (value <= 0) return 0;
        return (int) Math.min(value, Integer.MAX_VALUE);
    }

    /**
     * Reads how far a sound is meant to carry, in blocks.
     *
     * <p>Clamped to something a sound can actually be: zero silences it, and the ceiling is the
     * furthest Minecraft will send a sound packet for before the client stops hearing it anyway.
     */
    private int readSoundRadius(ConfigurationSection cfg, String key, int fallback) {
        if (!cfg.isSet(key)) return fallback;

        Object raw = cfg.get(key);
        if (!(raw instanceof Number number)) {
            plugin.getLogger().warning("[Samsara] '" + key + "' is not a number (found '" + raw
                + "'); using " + fallback + ".");
            return fallback;
        }

        long value = number.longValue();
        if (value < 0) {
            plugin.getLogger().warning("[Samsara] '" + key + "' is negative (" + value
                + "); silencing the sound instead.");
            return 0;
        }
        if (value > MAX_SOUND_RADIUS) {
            plugin.getLogger().warning("[Samsara] '" + key + "' is " + value
                + " blocks, further than a sound carries; clamping to " + MAX_SOUND_RADIUS + ".");
            return MAX_SOUND_RADIUS;
        }
        return (int) value;
    }

    /**
     * Reads a whole number that has a workable range, falling back — with a warning — to the default
     * rather than letting a typo out of it. Same shape as the clamping in
     * {@link DimensionalTravelConfig}, and for the same reason.
     */
    private int bounded(ConfigurationSection cfg, String key, int fallback, int min, int max) {
        int value = cfg.getInt(key, fallback);
        if (value < min || value > max) {
            plugin.getLogger().warning("[Samsara] '" + key + "' must be between " + min + " and " + max
                + " (got " + value + "); using " + fallback + ".");
            return fallback;
        }
        return value;
    }

    public String getWorldName()                     { return worldName; }
    public SpawnAreaShape getSpawnAreaShape()        { return spawnAreaShape; }
    public boolean isRespectWorldBorder()            { return respectWorldBorder; }
    public int getSpawnMinDistanceFromZero()         { return spawnMinDistanceFromZero; }
    public int getSpawnMaxDistanceFromZero()         { return spawnMaxDistanceFromZero; }
    public int getDeathRespawnMinDistanceFromDeath() { return deathRespawnMinDistanceFromDeath; }
    public boolean isAvoidOcean()                    { return avoidOcean; }
    public boolean isAvoidLava()                     { return avoidLava; }
    public int getMaxSafeSpawnAttempts()             { return maxSafeSpawnAttempts; }
    public boolean isJournalEnabled()                { return journalEnabled; }
    public int getJournalMaxEntries()                { return journalMaxEntries; }
    public boolean isDropEnderChestOnDeath()         { return dropEnderChestOnDeath; }
    public int getEndPortalActivationSoundRadius()   { return endPortalActivationSoundRadius; }
    public boolean isArrivalPrepareBeforeJoin()      { return arrivalPrepareBeforeJoin; }
    public int getArrivalPreparationTimeoutSeconds() { return arrivalPreparationTimeoutSeconds; }
    public int getArrivalPreloadRadius()             { return arrivalPreloadRadius; }
    public DimensionalTravelConfig getDimensionalTravel() { return dimensionalTravel; }
    public PresentationConfig getPresentation()      { return presentation; }
    public SocialConfig getSocial()                  { return social; }
    public PathConfig getPaths()                     { return paths; }
}
