package dev.unrau.samsara.path;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import dev.unrau.samsara.data.Json;
import dev.unrau.samsara.data.PlayerData;
import dev.unrau.samsara.data.PlayerDataCodec;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Where a player's paths live on disk.
 *
 * <p>One directory per account, under {@code plugins/Samsara/paths/<uuid>/}:
 *
 * <ul>
 *   <li>{@code index.json} — which paths exist, what they are called, and which one is active.</li>
 *   <li>{@code <pathId>.json} — one file per <em>dormant</em> path, holding it whole.</li>
 * </ul>
 *
 * <p>The active path has no file here, because the active path is the player. Its Samsara record is
 * the ordinary one in {@code playerdata/<uuid>.json} and its Minecraft state is whatever the server
 * has in front of it. That asymmetry is the point: nothing in the plugin outside this package has to
 * know that paths exist at all, because everything they ask about is always about the path being
 * walked.
 *
 * <p>Items are stored as Minecraft's own NBT, base64'd — {@link ItemStack#serializeAsBytes()} — so a
 * stack put away on one version comes back on the next with its enchantments, its damage, its
 * custom name and its contents intact, run through the server's own data fixer if the format moved
 * underneath it.
 */
public class PathStore {

    private final File root;
    private final Logger logger;
    private final PlayerDataCodec codec;

    public PathStore(File dataFolder, Logger logger) {
        this(new File(dataFolder, "paths"), logger, new PlayerDataCodec(logger));
    }

    /** Direct constructor, used by tests and by callers that already have a directory. */
    public PathStore(File root, Logger logger, PlayerDataCodec codec) {
        this.root = root;
        this.logger = logger;
        this.codec = codec;
        if (!root.exists()) {
            root.mkdirs();
        }
    }

    // -------------------------------------------------------------------------
    // The index
    // -------------------------------------------------------------------------

    /** @return what this account holds, or null if it has never held anything */
    public PathIndex loadIndex(UUID account) {
        File file = indexFile(account);
        if (!file.isFile()) return null;

        JsonObject root = readObject(file, "the path index for " + account);
        if (root == null) return null;

        PathIndex index = new PathIndex();
        index.setActivePathId(Json.uuidOrNull(root, "activePathId"));

        JsonElement listed = root.get("paths");
        if (listed != null && listed.isJsonArray()) {
            for (JsonElement raw : listed.getAsJsonArray()) {
                if (!raw.isJsonObject()) continue;
                PlayerPath path = readPath(account, raw.getAsJsonObject());
                if (path != null) {
                    index.add(path);
                }
            }
        }

        return index.isEmpty() ? null : index;
    }

    public boolean saveIndex(UUID account, PathIndex index) {
        JsonObject root = new JsonObject();
        root.addProperty("dataVersion", PathIndex.CURRENT_DATA_VERSION);
        if (index.activePathId() != null) {
            root.addProperty("activePathId", index.activePathId().toString());
        }

        JsonArray paths = new JsonArray();
        for (PlayerPath path : index.paths()) {
            JsonObject object = new JsonObject();
            object.addProperty("id", path.id().toString());
            object.addProperty("name", path.name());
            object.addProperty("createdAt", path.createdAt());
            if (!path.companions().isEmpty()) {
                JsonArray companions = new JsonArray();
                path.companions().forEach(companions::add);
                object.add("companions", companions);
            }
            paths.add(object);
        }
        root.add("paths", paths);

        return Json.writeAtomically(indexFile(account), root, logger, "the path index for " + account);
    }

    private PlayerPath readPath(UUID account, JsonObject object) {
        UUID id = Json.uuidOrNull(object, "id");
        String name = Json.stringOrNull(object, "name");
        if (id == null || name == null || name.isBlank()) {
            logger.warning("[Samsara] Dropping a path from " + account
                + "'s index that has no usable id or name.");
            return null;
        }

        List<String> companions = new ArrayList<>();
        JsonElement listed = object.get("companions");
        if (listed != null && listed.isJsonArray()) {
            for (JsonElement raw : listed.getAsJsonArray()) {
                if (raw.isJsonPrimitive()) companions.add(raw.getAsString());
            }
        }

        return new PlayerPath(id, name, Json.longOr(object, "createdAt", 0L), companions);
    }

    // -------------------------------------------------------------------------
    // Dormant paths
    // -------------------------------------------------------------------------

    public boolean hasSnapshot(UUID account, UUID pathId) {
        return snapshotFile(account, pathId).isFile();
    }

    /** @return the path, held whole, or null if there is no readable file for it */
    public PathSnapshot loadSnapshot(UUID account, UUID pathId) {
        File file = snapshotFile(account, pathId);
        if (!file.isFile()) return null;

        JsonObject root = readObject(file, "the dormant path " + pathId + " of " + account);
        if (root == null) return null;

        JsonObject stored = Json.objectOrNull(root, "record");
        PlayerData record = stored == null ? new PlayerData() : codec.read(account, stored);

        JsonObject state = Json.objectOrNull(root, "state");
        if (state == null) {
            logger.severe("[Samsara] The dormant path " + pathId + " of " + account
                + " has no Minecraft state in it; it cannot be walked back into.");
            return null;
        }

        return new PathSnapshot(record, readState(state));
    }

    public boolean saveSnapshot(UUID account, UUID pathId, PathSnapshot snapshot) {
        JsonObject root = new JsonObject();
        root.addProperty("dataVersion", PathIndex.CURRENT_DATA_VERSION);
        root.addProperty("pathId", pathId.toString());
        root.add("record", codec.write(snapshot.record()));
        root.add("state", writeState(snapshot.state()));
        return Json.writeAtomically(snapshotFile(account, pathId), root, logger,
            "the dormant path " + pathId + " of " + account);
    }

    /**
     * Removes a dormant path's file, either because it has been walked back into or because it has
     * been abandoned.
     *
     * @return true when there is no longer a file there, whether or not this call is why
     */
    public boolean deleteSnapshot(UUID account, UUID pathId) {
        File file = snapshotFile(account, pathId);
        if (!file.exists()) return true;
        if (file.delete()) return true;

        logger.severe("[Samsara] Could not delete " + file.getAbsolutePath()
            + ". Until it is gone by hand, that path will be restored from it on the next join.");
        return false;
    }

    // -------------------------------------------------------------------------
    // Minecraft state, as JSON
    // -------------------------------------------------------------------------

    private JsonObject writeState(IncarnationState state) {
        JsonObject object = new JsonObject();

        JsonObject at = new JsonObject();
        at.addProperty("world", state.worldName());
        at.addProperty("x", state.x());
        at.addProperty("y", state.y());
        at.addProperty("z", state.z());
        at.addProperty("yaw", state.yaw());
        at.addProperty("pitch", state.pitch());
        object.add("location", at);

        object.add("inventory", writeItems(state.inventory()));
        object.addProperty("heldSlot", state.heldSlot());
        object.add("enderChest", writeItems(state.enderChest()));

        object.addProperty("level", state.level());
        object.addProperty("exp", state.exp());
        object.addProperty("totalExperience", state.totalExperience());

        object.addProperty("health", state.health());
        object.addProperty("foodLevel", state.foodLevel());
        object.addProperty("saturation", state.saturation());
        object.addProperty("exhaustion", state.exhaustion());

        object.addProperty("remainingAir", state.remainingAir());
        object.addProperty("fireTicks", state.fireTicks());
        object.addProperty("fallDistance", state.fallDistance());

        if (state.gameMode() != null) {
            object.addProperty("gameMode", state.gameMode().name());
        }

        JsonArray effects = new JsonArray();
        for (PotionEffect effect : state.effects()) {
            JsonObject one = new JsonObject();
            one.addProperty("type", effect.getType().getKey().toString());
            one.addProperty("duration", effect.getDuration());
            one.addProperty("amplifier", effect.getAmplifier());
            one.addProperty("ambient", effect.isAmbient());
            one.addProperty("particles", effect.hasParticles());
            one.addProperty("icon", effect.hasIcon());
            effects.add(one);
        }
        object.add("effects", effects);

        return object;
    }

    private IncarnationState readState(JsonObject object) {
        JsonObject at = Json.objectOrNull(object, "location");
        String world = at == null ? "" : Json.stringOr(at, "world", "");

        return new IncarnationState(
            world,
            at == null ? 0 : Json.doubleOr(at, "x", 0),
            at == null ? 0 : Json.doubleOr(at, "y", 0),
            at == null ? 0 : Json.doubleOr(at, "z", 0),
            at == null ? 0f : Json.floatOr(at, "yaw", 0f),
            at == null ? 0f : Json.floatOr(at, "pitch", 0f),
            readItems(object.get("inventory"), IncarnationState.INVENTORY_SLOTS),
            Json.intOr(object, "heldSlot", 0),
            readItems(object.get("enderChest"), IncarnationState.ENDER_CHEST_SLOTS),
            Json.intOr(object, "level", 0),
            Json.floatOr(object, "exp", 0f),
            Json.intOr(object, "totalExperience", 0),
            Json.doubleOr(object, "health", 20.0),
            Json.intOr(object, "foodLevel", 20),
            Json.floatOr(object, "saturation", 5f),
            Json.floatOr(object, "exhaustion", 0f),
            Json.intOr(object, "remainingAir", 300),
            Json.intOr(object, "fireTicks", 0),
            Json.floatOr(object, "fallDistance", 0f),
            readGameMode(Json.stringOrNull(object, "gameMode")),
            readEffects(object.get("effects"))
        );
    }

    private JsonArray writeItems(ItemStack[] slots) {
        JsonArray array = new JsonArray();
        for (ItemStack item : slots) {
            if (item == null || item.getType().isAir()) {
                array.add(JsonNull.INSTANCE);
                continue;
            }
            try {
                array.add(Base64.getEncoder().encodeToString(item.serializeAsBytes()));
            } catch (RuntimeException e) {
                // One unserialisable stack must not cost a player the other forty. Losing a slot is
                // bad; refusing to store the path at all would be losing the whole existence.
                logger.log(Level.WARNING, "[Samsara] Could not store a " + item.getType()
                    + " while putting a path away; that slot will come back empty.", e);
                array.add(JsonNull.INSTANCE);
            }
        }
        return array;
    }

    private ItemStack[] readItems(JsonElement element, int size) {
        ItemStack[] slots = new ItemStack[size];
        if (element == null || !element.isJsonArray()) return slots;

        JsonArray array = element.getAsJsonArray();
        for (int i = 0; i < size && i < array.size(); i++) {
            JsonElement raw = array.get(i);
            if (raw == null || raw.isJsonNull() || !raw.isJsonPrimitive()) continue;
            try {
                slots[i] = ItemStack.deserializeBytes(Base64.getDecoder().decode(raw.getAsString()));
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "[Samsara] Could not read the item in slot " + i
                    + " of a dormant path; it comes back empty.", e);
            }
        }
        return slots;
    }

    private List<PotionEffect> readEffects(JsonElement element) {
        List<PotionEffect> effects = new ArrayList<>();
        if (element == null || !element.isJsonArray()) return effects;

        for (JsonElement raw : element.getAsJsonArray()) {
            if (!raw.isJsonObject()) continue;
            JsonObject one = raw.getAsJsonObject();

            NamespacedKey key = NamespacedKey.fromString(Json.stringOr(one, "type", ""));
            PotionEffectType type = key == null ? null : Registry.EFFECT.get(key);
            if (type == null) {
                // A modded or removed effect. Dropping it is the honest outcome — the alternative is
                // refusing to restore a path over an effect that had seconds left on it.
                logger.warning("[Samsara] A dormant path carries the unknown potion effect '"
                    + Json.stringOr(one, "type", "") + "'; it is dropped.");
                continue;
            }
            effects.add(new PotionEffect(type,
                Json.intOr(one, "duration", 0),
                Json.intOr(one, "amplifier", 0),
                Json.boolOr(one, "ambient", false),
                Json.boolOr(one, "particles", true),
                Json.boolOr(one, "icon", true)));
        }
        return effects;
    }

    private GameMode readGameMode(String raw) {
        if (raw == null) return null;
        try {
            return GameMode.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Plumbing
    // -------------------------------------------------------------------------

    /**
     * Reads a file, setting it aside rather than overwriting it if it cannot be understood.
     *
     * <p>Same bargain as {@code PlayerDataStore}: whatever the file held is still on disk to be
     * looked at, and the caller is told nothing rather than told something wrong. For a path that
     * means the path is unreachable until an operator looks — which is far better than a path that
     * silently comes back empty and takes an existence's belongings with it.
     */
    private JsonObject readObject(File file, String subject) {
        try {
            JsonElement parsed = Json.parse(file);
            if (parsed.isJsonObject()) return parsed.getAsJsonObject();
            quarantine(file, subject, "it does not contain a JSON object");
        } catch (JsonParseException e) {
            quarantine(file, subject, "it is not readable JSON (" + e.getMessage() + ")");
        } catch (IOException e) {
            logger.log(Level.SEVERE, "[Samsara] Failed to read " + subject, e);
        }
        return null;
    }

    private void quarantine(File file, String subject, String why) {
        File spoiled = new File(file.getParentFile(), file.getName() + ".corrupt");
        String outcome = file.renameTo(spoiled)
            ? "It has been renamed to " + spoiled.getName() + "."
            : "It could not be renamed and will be overwritten if that path is written again.";
        logger.severe("[Samsara] Could not read " + subject + " because " + why + ". " + outcome);
    }

    private File directoryFor(UUID account) {
        return new File(root, account.toString());
    }

    private File indexFile(UUID account) {
        return new File(directoryFor(account), "index.json");
    }

    private File snapshotFile(UUID account, UUID pathId) {
        return new File(directoryFor(account), pathId + ".json");
    }
}
