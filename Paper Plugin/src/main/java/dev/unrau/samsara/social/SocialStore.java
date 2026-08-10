package dev.unrau.samsara.social;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One JSON file per player under {@code plugins/Samsara/social/}, holding who that player knows.
 *
 * <p>Deliberately not part of {@code playerdata/}. That directory is rewritten on every death and
 * every crossing of a dimension boundary, by several callers, each doing its own read-modify-write;
 * dropping a long-lived, frequently-touched social graph into the middle of that would mean two
 * subsystems racing to be the last to save the same file. A relationship is also not a fact about a
 * life, and it does not belong in the record that a life owns.
 *
 * <p><b>Every online player is held in the cache.</b> That is the invariant the rest of the package
 * relies on: a record read for somebody who is online is always the live one, so a change made to a
 * contact who happens to be logged in is seen immediately by the chat listener, and an offline
 * player's file can be edited on their behalf and written straight back without anybody's copy going
 * stale.
 */
public class SocialStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final File dataDir;
    private final Logger logger;

    /** Records for players who are online, plus anybody a caller has explicitly held. */
    private final Map<UUID, SocialData> cache = new ConcurrentHashMap<>();

    public SocialStore(JavaPlugin plugin) {
        this(new File(plugin.getDataFolder(), "social"), plugin.getLogger());
    }

    /** Direct constructor, used by tests and by callers that already have a data directory. */
    public SocialStore(File dataDir, Logger logger) {
        this.dataDir = dataDir;
        this.logger = logger;
        if (!dataDir.exists()) {
            dataDir.mkdirs();
        }
    }

    /**
     * The player's record: the live one if they are online, otherwise a copy read from disk.
     *
     * <p>A detached copy must be written back through {@link #save} by whoever changed it. That is
     * the whole cost of not caching offline players, and it is worth paying: a server that has seen
     * ten thousand players should not hold ten thousand social graphs in memory to answer a question
     * about the six who are logged in.
     */
    public SocialData load(UUID uuid) {
        SocialData cached = cache.get(uuid);
        return cached != null ? cached : read(uuid);
    }

    /** Brings a player's record into the cache and keeps it there. Called when they join. */
    public SocialData hold(UUID uuid, String name) {
        SocialData data = cache.computeIfAbsent(uuid, this::read);
        data.setName(name);
        return data;
    }

    /** Writes a player's record out and drops it from the cache. Called when they leave. */
    public void release(UUID uuid) {
        SocialData data = cache.remove(uuid);
        if (data != null && data.isDirty()) {
            save(uuid, data);
        }
    }

    /** Writes a record that has changed, and nothing else. */
    public void saveIfDirty(UUID uuid, SocialData data) {
        if (data.isDirty()) {
            save(uuid, data);
        }
    }

    /**
     * Tidies and writes every held record that has changed.
     *
     * <p>The pruning happens here, on the way to disk, rather than on a timer of its own: a file is
     * tidied exactly when it is about to be written, so a player who has walked past a hundred people
     * over a year never accumulates a hundred entries on disk.
     *
     * @param fade how time apart is charged; anything it has taken to nothing is dropped
     */
    public void flushAll(long nowMillis, SocialData.Fade fade) {
        cache.forEach((uuid, data) -> {
            data.pruneProximity(nowMillis, fade);
            saveIfDirty(uuid, data);
        });
    }

    /** Writes everything and empties the cache. Called when the plugin stops. */
    public void shutdown(long nowMillis, SocialData.Fade fade) {
        flushAll(nowMillis, fade);
        cache.clear();
    }

    // -------------------------------------------------------------------------
    // Reading
    // -------------------------------------------------------------------------

    private SocialData read(UUID uuid) {
        File file = fileFor(uuid);
        if (!file.isFile()) return new SocialData();

        JsonObject root;
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                quarantine(uuid, file, "it does not contain a JSON object");
                return new SocialData();
            }
            root = parsed.getAsJsonObject();
        } catch (JsonParseException e) {
            quarantine(uuid, file, "it is not readable JSON (" + e.getMessage() + ")");
            return new SocialData();
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to read social data for " + uuid, e);
            return new SocialData();
        }

        SocialData data = new SocialData();
        data.setDataVersion(intOr(root, "dataVersion", SocialData.CURRENT_DATA_VERSION));
        data.setName(stringOr(root, "name", ""));

        JsonElement auto = root.get("autoContacts");
        if (auto != null && auto.isJsonPrimitive()) {
            data.setAutoContacts(auto.getAsBoolean());
        }

        readNameMap(root, "contacts", data::addContact);
        readNameMap(root, "ignored", data::ignore);

        JsonElement suppressed = root.get("autoSuppressed");
        if (suppressed != null && suppressed.isJsonArray()) {
            for (JsonElement element : suppressed.getAsJsonArray()) {
                UUID other = uuidOrNull(element.isJsonPrimitive() ? element.getAsString() : null);
                if (other != null) data.suppressAuto(other);
            }
        }

        JsonObject progress = objectOrNull(root, "proximity");
        if (progress != null) {
            for (Map.Entry<String, JsonElement> entry : progress.entrySet()) {
                UUID other = uuidOrNull(entry.getKey());
                if (other == null || !entry.getValue().isJsonObject()) continue;
                JsonObject value = entry.getValue().getAsJsonObject();
                data.restoreProximity(other, doubleOr(value, "seconds", 0),
                    longOr(value, "at", 0L));
            }
        }

        // Everything above went in through the ordinary setters, which is how the record ends up
        // marked dirty by the act of being read. Nothing has actually changed, so say so.
        data.clearDirty();
        return data;
    }

    /** Reads a {@code {uuid: name}} object, skipping entries whose key is not an id. */
    private void readNameMap(JsonObject root, String key, java.util.function.BiConsumer<UUID, String> into) {
        JsonObject section = objectOrNull(root, key);
        if (section == null) return;

        for (Map.Entry<String, JsonElement> entry : section.entrySet()) {
            UUID other = uuidOrNull(entry.getKey());
            if (other == null) continue;
            JsonElement name = entry.getValue();
            into.accept(other, name != null && name.isJsonPrimitive() ? name.getAsString() : "");
        }
    }

    // -------------------------------------------------------------------------
    // Writing
    // -------------------------------------------------------------------------

    /** Writes a record out, exactly as it stands. */
    public void save(UUID uuid, SocialData data) {
        JsonObject root = new JsonObject();
        root.addProperty("dataVersion", SocialData.CURRENT_DATA_VERSION);
        root.addProperty("name", data.getName());
        if (data.getAutoContacts() != null) {
            root.addProperty("autoContacts", data.getAutoContacts());
        }

        root.add("contacts", nameMap(data.getContacts()));
        if (!data.getIgnored().isEmpty()) {
            root.add("ignored", nameMap(data.getIgnored()));
        }
        if (!data.getAutoSuppressed().isEmpty()) {
            JsonArray suppressed = new JsonArray();
            for (UUID other : data.getAutoSuppressed()) suppressed.add(other.toString());
            root.add("autoSuppressed", suppressed);
        }

        List<Map.Entry<UUID, SocialData.Proximity>> entries = data.proximitySnapshot();
        if (!entries.isEmpty()) {
            JsonObject progress = new JsonObject();
            for (Map.Entry<UUID, SocialData.Proximity> entry : entries) {
                JsonObject value = new JsonObject();
                value.addProperty("seconds", Math.round(entry.getValue().seconds() * 10) / 10.0);
                value.addProperty("at", entry.getValue().lastSampleMillis());
                progress.add(entry.getKey().toString(), value);
            }
            root.add("proximity", progress);
        }

        writeAtomically(uuid, fileFor(uuid), root);
        data.clearDirty();
    }

    private JsonObject nameMap(Map<UUID, String> source) {
        JsonObject object = new JsonObject();
        source.forEach((uuid, name) -> object.addProperty(uuid.toString(), name));
        return object;
    }

    /**
     * Writes to a temporary file and moves it into place, so a server that dies mid-write leaves the
     * previous record intact. A truncated social file would read as a player with no contacts and no
     * ignore list — relationships silently deleted by a power cut.
     */
    private void writeAtomically(UUID uuid, File file, JsonObject root) {
        Path target = file.toPath();
        Path temp = target.resolveSibling(file.getName() + ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
                writer.write(System.lineSeparator());
            }
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to save social data for " + uuid, e);
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // Nothing more can be done here; the next save overwrites it.
            }
        }
    }

    /**
     * Sets an unreadable file aside instead of overwriting it. The player is treated as knowing
     * nobody — which is wrong, and recoverable, and better than deleting the evidence.
     */
    private void quarantine(UUID uuid, File file, String why) {
        File spoiled = new File(file.getParentFile(), file.getName() + ".corrupt");
        String outcome = file.renameTo(spoiled)
            ? "It has been renamed to " + spoiled.getName() + "."
            : "It could not be renamed and will be overwritten on the next save.";
        logger.severe("Social data for " + uuid + " could not be read because " + why
            + ". Their contacts and ignore list start empty. " + outcome);
    }

    // -------------------------------------------------------------------------
    // Plumbing
    // -------------------------------------------------------------------------

    private static UUID uuidOrNull(String raw) {
        if (raw == null) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static JsonObject objectOrNull(JsonObject root, String key) {
        JsonElement element = root.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static String stringOr(JsonObject root, String key, String fallback) {
        JsonElement element = root.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : fallback;
    }

    private static int intOr(JsonObject root, String key, int fallback) {
        return (int) longOr(root, key, fallback);
    }

    private static long longOr(JsonObject root, String key, long fallback) {
        JsonElement element = root.get(key);
        if (element == null || !element.isJsonPrimitive()) return fallback;
        try {
            return element.getAsLong();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static double doubleOr(JsonObject root, String key, double fallback) {
        JsonElement element = root.get(key);
        if (element == null || !element.isJsonPrimitive()) return fallback;
        try {
            return element.getAsDouble();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private File fileFor(UUID uuid) {
        return new File(dataDir, uuid + ".json");
    }
}
