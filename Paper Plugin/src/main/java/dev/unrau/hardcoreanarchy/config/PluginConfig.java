package dev.unrau.hardcoreanarchy.config;

import org.bukkit.plugin.java.JavaPlugin;

public class PluginConfig {

    private final JavaPlugin plugin;

    private String worldName;
    private int firstJoinMinDistance;
    private int firstJoinMaxDistance;
    private int deathRespawnMinDistanceFromZero;
    private int deathRespawnMaxDistanceFromZero;
    private int deathRespawnMinDistanceFromDeath;
    private boolean avoidOcean;
    private boolean avoidLava;
    private int maxSafeSpawnAttempts;
    private boolean logSpawnLocations;
    private boolean clearEnderChestOnDeath;
    private boolean dropEnderChestContentsOnDeath;
    private boolean disableBedRespawn;
    private boolean disableRespawnAnchorRespawn;

    private String msgDeathScattered;
    private String msgCalculatingExile;
    private String msgBedsDoNotBind;
    private String msgAnchorsDoNotBind;

    public PluginConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.reloadConfig();
        var cfg = plugin.getConfig();

        worldName                       = cfg.getString("worldName", "world");
        firstJoinMinDistance            = cfg.getInt("firstJoinMinDistanceFromZero", 50000);
        firstJoinMaxDistance            = cfg.getInt("firstJoinMaxDistanceFromZero", 500000);
        deathRespawnMinDistanceFromZero = cfg.getInt("deathRespawnMinDistanceFromZero", 250000);
        deathRespawnMaxDistanceFromZero = cfg.getInt("deathRespawnMaxDistanceFromZero", 5000000);
        deathRespawnMinDistanceFromDeath= cfg.getInt("deathRespawnMinDistanceFromDeath", 500000);
        avoidOcean                      = cfg.getBoolean("avoidOcean", true);
        avoidLava                       = cfg.getBoolean("avoidLava", true);
        maxSafeSpawnAttempts            = cfg.getInt("maxSafeSpawnAttempts", 64);
        logSpawnLocations               = cfg.getBoolean("logSpawnLocations", true);
        clearEnderChestOnDeath          = cfg.getBoolean("clearEnderChestOnDeath", true);
        dropEnderChestContentsOnDeath   = cfg.getBoolean("dropEnderChestContentsOnDeath", true);
        disableBedRespawn               = cfg.getBoolean("disableBedRespawn", true);
        disableRespawnAnchorRespawn     = cfg.getBoolean("disableRespawnAnchorRespawn", true);

        msgDeathScattered   = color(cfg.getString("messages.deathScattered",   "Death has scattered you."));
        msgCalculatingExile = color(cfg.getString("messages.calculatingExile", "Calculating your exile location..."));
        msgBedsDoNotBind    = color(cfg.getString("messages.bedsDoNotBind",    "Beds do not bind you in this world."));
        msgAnchorsDoNotBind = color(cfg.getString("messages.anchorsDoNotBind", "Anchors do not bind you in this world."));
    }

    private static String color(String s) {
        return s.replaceAll("&([0-9a-fA-FkKlLmMnNoOrR])", "§$1");
    }

    public String getWorldName()                     { return worldName; }
    public int getFirstJoinMinDistance()             { return firstJoinMinDistance; }
    public int getFirstJoinMaxDistance()             { return firstJoinMaxDistance; }
    public int getDeathRespawnMinDistanceFromZero()  { return deathRespawnMinDistanceFromZero; }
    public int getDeathRespawnMaxDistanceFromZero()  { return deathRespawnMaxDistanceFromZero; }
    public int getDeathRespawnMinDistanceFromDeath() { return deathRespawnMinDistanceFromDeath; }
    public boolean isAvoidOcean()                    { return avoidOcean; }
    public boolean isAvoidLava()                     { return avoidLava; }
    public int getMaxSafeSpawnAttempts()             { return maxSafeSpawnAttempts; }
    public boolean isLogSpawnLocations()             { return logSpawnLocations; }
    public boolean isClearEnderChestOnDeath()        { return clearEnderChestOnDeath; }
    public boolean isDropEnderChestContentsOnDeath() { return dropEnderChestContentsOnDeath; }
    public boolean isDisableBedRespawn()             { return disableBedRespawn; }
    public boolean isDisableRespawnAnchorRespawn()   { return disableRespawnAnchorRespawn; }
    public String getMsgDeathScattered()             { return msgDeathScattered; }
    public String getMsgCalculatingExile()           { return msgCalculatingExile; }
    public String getMsgBedsDoNotBind()              { return msgBedsDoNotBind; }
    public String getMsgAnchorsDoNotBind()           { return msgAnchorsDoNotBind; }
}
