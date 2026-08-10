package dev.unrau.samsara.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One JSON file per player, holding everything the plugin knows about the path they are currently
 * walking: the state it acts on, and the journal of what has happened to them.
 *
 * <p>This is the <em>active</em> path and nothing else. A player may keep several paths, each an
 * independent Minecraft existence, but only one of them is being lived at a time — and the one being
 * lived is the one whose record is here, exactly as it was before paths existed. The dormant ones
 * are files of their own, held by {@link dev.unrau.samsara.path.PathStore}, and nothing in the rest
 * of the plugin has to know they exist: everything that asks this store a question is asking about
 * the player in front of it.
 *
 * <p>Files written by earlier versions are YAML. They are read once, rewritten as JSON and the
 * YAML deleted, so an upgraded server converts itself as its players log in.
 */
public class PlayerDataStore {

    private final File dataDir;
    private final Logger logger;
    private final PlayerDataCodec codec;

    public PlayerDataStore(JavaPlugin plugin) {
        this(new File(plugin.getDataFolder(), "playerdata"), plugin.getLogger());
    }

    /** Direct constructor, used by tests and by callers that already have a data directory. */
    public PlayerDataStore(File dataDir, Logger logger) {
        this.dataDir = dataDir;
        this.logger = logger;
        this.codec = new PlayerDataCodec(logger);
        if (!dataDir.exists()) {
            dataDir.mkdirs();
        }
    }

    public PlayerData load(UUID uuid) {
        File file = fileFor(uuid);
        if (file.exists()) {
            PlayerData data = readJson(uuid, file);
            return data != null ? data : new PlayerData();
        }

        File legacy = legacyFileFor(uuid);
        if (legacy.exists()) {
            return convertLegacyFile(uuid, legacy);
        }

        return new PlayerData();
    }

    public void save(UUID uuid, PlayerData data) {
        Json.writeAtomically(fileFor(uuid), codec.write(data), logger, "player data for " + uuid);
    }

    /** The record as JSON, for a caller that keeps it somewhere other than this directory. */
    public PlayerDataCodec codec() {
        return codec;
    }

    /**
     * Removes a player's active record entirely.
     *
     * <p>Used when a path is abandoned and the record that described it must not survive into
     * whatever the player walks next. There is nothing to keep: the path it belonged to is gone.
     */
    public void delete(UUID uuid) {
        File file = fileFor(uuid);
        if (file.exists() && !file.delete()) {
            logger.warning("Could not delete the player record at " + file.getName()
                + "; it will be overwritten on the next save.");
        }
    }

    // -------------------------------------------------------------------------
    // Reading
    // -------------------------------------------------------------------------

    /** @return the record, or null if the file could not be read and has been set aside */
    private PlayerData readJson(UUID uuid, File file) {
        JsonObject root;
        try {
            JsonElement parsed = Json.parse(file);
            if (!parsed.isJsonObject()) {
                quarantine(uuid, file, "it does not contain a JSON object");
                return null;
            }
            root = parsed.getAsJsonObject();
        } catch (JsonParseException e) {
            quarantine(uuid, file, "it is not readable JSON (" + e.getMessage() + ")");
            return null;
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to read player data for " + uuid, e);
            return null;
        }

        return codec.read(uuid, root);
    }

    /**
     * Reads a record written by an earlier version, rewrites it as JSON and removes the YAML.
     * The conversion is done at the moment the file is first needed, so no server-wide migration
     * pass has to run and a player who never logs in again is simply left alone.
     */
    private PlayerData convertLegacyFile(UUID uuid, File legacy) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(legacy);
        PlayerData data = new PlayerData();

        // Version 1 files predate lifeId and endExpedition; both are simply absent, so there is
        // nothing to rewrite. Version 3 and below are YAML and have no journal.
        data.setDataVersion(yaml.getInt("dataVersion", 1));
        codec.readInto(uuid, data, yamlToJson(yaml));

        save(uuid, data);
        data.setDataVersion(PlayerData.CURRENT_DATA_VERSION);

        if (legacy.delete()) {
            logger.info("Converted player data for " + uuid + " from " + legacy.getName()
                + " to JSON.");
        } else {
            logger.warning("Converted player data for " + uuid + " to JSON, but " + legacy.getName()
                + " could not be deleted. Delete it by hand — until then it is ignored.");
        }
        return data;
    }

    /**
     * Sets an unreadable file aside instead of overwriting it. The player is treated as new — which
     * costs them an exile — but whatever the file held is still on disk to be looked at.
     */
    private void quarantine(UUID uuid, File file, String why) {
        File spoiled = new File(file.getParentFile(), file.getName() + ".corrupt");
        String outcome = file.renameTo(spoiled)
            ? "It has been renamed to " + spoiled.getName() + "."
            : "It could not be renamed and will be overwritten on the next save.";
        logger.severe("Player data for " + uuid + " could not be read because " + why
            + ". They will be treated as a new player. " + outcome);
    }

    // -------------------------------------------------------------------------
    // Plumbing
    // -------------------------------------------------------------------------

    /** Re-reads a YAML record as the equivalent JSON object, so one reader handles both formats. */
    private JsonObject yamlToJson(YamlConfiguration yaml) {
        JsonObject root = new JsonObject();
        copyYamlValues(yaml, root);
        return root;
    }

    private void copyYamlValues(ConfigurationSection section, JsonObject into) {
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            if (value instanceof ConfigurationSection child) {
                JsonObject nested = new JsonObject();
                copyYamlValues(child, nested);
                into.add(key, nested);
            } else if (value instanceof Number number) {
                into.addProperty(key, number);
            } else if (value instanceof Boolean bool) {
                into.addProperty(key, bool);
            } else if (value != null) {
                into.addProperty(key, value.toString());
            }
        }
    }

    private File fileFor(UUID uuid) {
        return new File(dataDir, uuid + ".json");
    }

    /** Where versions before 4 kept the same record. */
    private File legacyFileFor(UUID uuid) {
        return new File(dataDir, uuid + ".yml");
    }
}
