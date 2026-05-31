package dev.unrau.hardcoreanarchy.data;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.logging.Level;

public class PlayerDataStore {

    private final File dataDir;
    private final JavaPlugin plugin;

    public PlayerDataStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.dataDir = new File(plugin.getDataFolder(), "playerdata");
        if (!dataDir.exists()) {
            dataDir.mkdirs();
        }
    }

    public PlayerData load(UUID uuid) {
        File file = fileFor(uuid);
        if (!file.exists()) {
            return new PlayerData();
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        PlayerData data = new PlayerData();

        data.setHasJoinedBefore(yaml.getBoolean("hasJoinedBefore", false));
        data.setCalculatingRespawn(yaml.getBoolean("calculatingRespawn", false));
        data.setNeedsDelayedTeleport(yaml.getBoolean("needsDelayedTeleport", false));

        if (yaml.contains("firstSpawn")) {
            data.setFirstSpawn(
                yaml.getString("firstSpawn.world"),
                yaml.getDouble("firstSpawn.x"),
                yaml.getDouble("firstSpawn.y"),
                yaml.getDouble("firstSpawn.z")
            );
        }
        if (yaml.contains("lastDeath")) {
            data.setLastDeath(
                yaml.getString("lastDeath.world"),
                yaml.getDouble("lastDeath.x"),
                yaml.getDouble("lastDeath.y"),
                yaml.getDouble("lastDeath.z")
            );
        }
        if (yaml.getBoolean("hasPendingRespawn", false)) {
            data.setPendingRespawn(
                yaml.getString("pendingRespawn.world"),
                yaml.getDouble("pendingRespawn.x"),
                yaml.getDouble("pendingRespawn.y"),
                yaml.getDouble("pendingRespawn.z")
            );
        }
        if (yaml.contains("lastExile")) {
            data.setLastExile(
                yaml.getString("lastExile.world"),
                yaml.getDouble("lastExile.x"),
                yaml.getDouble("lastExile.y"),
                yaml.getDouble("lastExile.z")
            );
        }

        // deathCount stored as int under "deathCount"
        // We increment separately so just read it back
        int deaths = yaml.getInt("deathCount", 0);
        for (int i = 0; i < deaths; i++) data.incrementDeathCount();

        return data;
    }

    public void save(UUID uuid, PlayerData data) {
        File file = fileFor(uuid);
        YamlConfiguration yaml = new YamlConfiguration();

        yaml.set("hasJoinedBefore", data.isHasJoinedBefore());
        yaml.set("calculatingRespawn", data.isCalculatingRespawn());
        yaml.set("needsDelayedTeleport", data.isNeedsDelayedTeleport());
        yaml.set("deathCount", data.getDeathCount());

        if (data.getFirstSpawnWorld() != null) {
            yaml.set("firstSpawn.world", data.getFirstSpawnWorld());
            yaml.set("firstSpawn.x", data.getFirstSpawnX());
            yaml.set("firstSpawn.y", data.getFirstSpawnY());
            yaml.set("firstSpawn.z", data.getFirstSpawnZ());
        }
        if (data.getLastDeathWorld() != null) {
            yaml.set("lastDeath.world", data.getLastDeathWorld());
            yaml.set("lastDeath.x", data.getLastDeathX());
            yaml.set("lastDeath.y", data.getLastDeathY());
            yaml.set("lastDeath.z", data.getLastDeathZ());
        }
        yaml.set("hasPendingRespawn", data.isHasPendingRespawn());
        if (data.isHasPendingRespawn()) {
            yaml.set("pendingRespawn.world", data.getPendingRespawnWorld());
            yaml.set("pendingRespawn.x", data.getPendingRespawnX());
            yaml.set("pendingRespawn.y", data.getPendingRespawnY());
            yaml.set("pendingRespawn.z", data.getPendingRespawnZ());
        }
        if (data.getLastExileWorld() != null) {
            yaml.set("lastExile.world", data.getLastExileWorld());
            yaml.set("lastExile.x", data.getLastExileX());
            yaml.set("lastExile.y", data.getLastExileY());
            yaml.set("lastExile.z", data.getLastExileZ());
        }

        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save player data for " + uuid, e);
        }
    }

    private File fileFor(UUID uuid) {
        return new File(dataDir, uuid.toString() + ".yml");
    }
}
