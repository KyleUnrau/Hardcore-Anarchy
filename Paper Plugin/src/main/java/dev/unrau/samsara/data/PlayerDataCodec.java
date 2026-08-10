package dev.unrau.samsara.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * One {@link PlayerData} record, as JSON.
 *
 * <p>Split out from {@link PlayerDataStore} because a record and a file are no longer the same
 * thing. A player's active path is the one whose record sits in {@code playerdata/<uuid>.json};
 * their dormant paths each hold a record of their own, folded into a larger file alongside the
 * Minecraft state that belongs with it. Both are read and written by this, so a field added to a
 * record is a field a dormant path keeps too — the alternative is two writers that agree until one
 * of them is edited.
 */
public class PlayerDataCodec {

    private final Logger logger;

    public PlayerDataCodec(Logger logger) {
        this.logger = logger;
    }

    // -------------------------------------------------------------------------
    // Writing
    // -------------------------------------------------------------------------

    public JsonObject write(PlayerData data) {
        JsonObject root = new JsonObject();

        root.addProperty("dataVersion", PlayerData.CURRENT_DATA_VERSION);
        root.addProperty("hasJoinedBefore", data.isHasJoinedBefore());
        root.addProperty("calculatingRespawn", data.isCalculatingRespawn());
        root.addProperty("needsDelayedTeleport", data.isNeedsDelayedTeleport());
        root.addProperty("deathCount", data.getDeathCount());

        if (data.getLifeId() != null) {
            root.addProperty("lifeId", data.getLifeId().toString());
        }
        if (data.getWorldUid() != null) {
            root.addProperty("worldUid", data.getWorldUid().toString());
        }

        if (data.getFirstSpawnWorld() != null) {
            root.add("firstSpawn", position(data.getFirstSpawnWorld(),
                data.getFirstSpawnX(), data.getFirstSpawnY(), data.getFirstSpawnZ()));
        }
        if (data.getLastDeathWorld() != null) {
            root.add("lastDeath", position(data.getLastDeathWorld(),
                data.getLastDeathX(), data.getLastDeathY(), data.getLastDeathZ()));
        }
        root.addProperty("hasPendingRespawn", data.isHasPendingRespawn());
        if (data.isHasPendingRespawn()) {
            root.add("pendingRespawn", position(data.getPendingRespawnWorld(),
                data.getPendingRespawnX(), data.getPendingRespawnY(), data.getPendingRespawnZ()));
        }
        if (data.getLastExileWorld() != null) {
            root.add("lastExile", position(data.getLastExileWorld(),
                data.getLastExileX(), data.getLastExileY(), data.getLastExileZ()));
        }

        // The object exists only while an expedition is open; closing one removes it entirely.
        EndExpedition expedition = data.getEndExpedition();
        if (expedition != null) {
            root.add("endExpedition", writeExpedition(expedition));
        }

        if (!data.getJournal().isEmpty()) {
            root.add("journal", writeJournal(data.getJournal()));
        }

        return root;
    }

    private JsonObject position(String world, double x, double y, double z) {
        JsonObject object = new JsonObject();
        object.addProperty("world", world);
        object.addProperty("x", x);
        object.addProperty("y", y);
        object.addProperty("z", z);
        return object;
    }

    private JsonObject writeExpedition(EndExpedition expedition) {
        JsonObject object = new JsonObject();
        object.addProperty("lifeId", expedition.getLifeId().toString());
        object.add("origin", position(expedition.getOriginWorld(),
            expedition.getOriginX(), expedition.getOriginY(), expedition.getOriginZ()));
        object.add("return", position(expedition.getReturnWorld(),
            expedition.getReturnX(), expedition.getReturnY(), expedition.getReturnZ()));
        object.add("end", position(expedition.getEndWorld(),
            expedition.getEndX(), expedition.getEndY(), expedition.getEndZ()));
        object.addProperty("region", expedition.getRegionKey());
        object.addProperty("openedAt", expedition.getOpenedAt());
        return object;
    }

    private JsonArray writeJournal(List<JournalEntry> entries) {
        JsonArray array = new JsonArray();
        for (JournalEntry entry : entries) {
            JsonObject object = new JsonObject();
            object.addProperty("at", entry.at().toString());
            object.addProperty("reason", entry.reason().name());
            object.addProperty("player", entry.playerName());
            object.addProperty("world", entry.world());
            object.addProperty("x", entry.x());
            object.addProperty("y", entry.y());
            object.addProperty("z", entry.z());
            array.add(object);
        }
        return array;
    }

    // -------------------------------------------------------------------------
    // Reading
    // -------------------------------------------------------------------------

    /** Reads a whole record, including its stated version. */
    public PlayerData read(UUID uuid, JsonObject root) {
        PlayerData data = new PlayerData();
        data.setDataVersion(Json.intOr(root, "dataVersion", PlayerData.CURRENT_DATA_VERSION));
        readInto(uuid, data, root);
        return data;
    }

    /** Fills a record from the on-disk object, tolerating anything missing or unreadable. */
    public void readInto(UUID uuid, PlayerData data, JsonObject root) {
        data.setHasJoinedBefore(Json.boolOr(root, "hasJoinedBefore", false));
        data.setCalculatingRespawn(Json.boolOr(root, "calculatingRespawn", false));
        data.setNeedsDelayedTeleport(Json.boolOr(root, "needsDelayedTeleport", false));

        String lifeId = Json.stringOrNull(root, "lifeId");
        if (lifeId != null) {
            try {
                data.setLifeId(UUID.fromString(lifeId));
            } catch (IllegalArgumentException e) {
                logger.warning("Player data for " + uuid + " has an unreadable lifeId '" + lifeId
                    + "'; treating this life as unidentified.");
            }
        }

        String worldUid = Json.stringOrNull(root, "worldUid");
        if (worldUid != null) {
            try {
                data.setWorldUid(UUID.fromString(worldUid));
            } catch (IllegalArgumentException e) {
                logger.warning("Player data for " + uuid + " has an unreadable worldUid '" + worldUid
                    + "'; it will be rewritten on the next join.");
            }
        }

        JsonObject firstSpawn = Json.objectOrNull(root, "firstSpawn");
        if (firstSpawn != null) {
            data.setFirstSpawn(Json.stringOrNull(firstSpawn, "world"),
                Json.doubleOr(firstSpawn, "x", 0), Json.doubleOr(firstSpawn, "y", 0),
                Json.doubleOr(firstSpawn, "z", 0));
        }
        JsonObject lastDeath = Json.objectOrNull(root, "lastDeath");
        if (lastDeath != null) {
            data.setLastDeath(Json.stringOrNull(lastDeath, "world"),
                Json.doubleOr(lastDeath, "x", 0), Json.doubleOr(lastDeath, "y", 0),
                Json.doubleOr(lastDeath, "z", 0));
        }
        JsonObject pendingRespawn = Json.objectOrNull(root, "pendingRespawn");
        if (Json.boolOr(root, "hasPendingRespawn", false) && pendingRespawn != null) {
            data.setPendingRespawn(Json.stringOrNull(pendingRespawn, "world"),
                Json.doubleOr(pendingRespawn, "x", 0), Json.doubleOr(pendingRespawn, "y", 0),
                Json.doubleOr(pendingRespawn, "z", 0));
        }
        JsonObject lastExile = Json.objectOrNull(root, "lastExile");
        if (lastExile != null) {
            data.setLastExile(Json.stringOrNull(lastExile, "world"),
                Json.doubleOr(lastExile, "x", 0), Json.doubleOr(lastExile, "y", 0),
                Json.doubleOr(lastExile, "z", 0));
        }

        JsonObject expedition = Json.objectOrNull(root, "endExpedition");
        if (expedition != null) {
            EndExpedition loaded = readExpedition(uuid, expedition);
            if (loaded != null) {
                data.setEndExpedition(loaded);
            }
        }

        int deaths = Json.intOr(root, "deathCount", 0);
        for (int i = 0; i < deaths; i++) data.incrementDeathCount();

        // Kept exactly as written: what the file holds is the history, and trimming belongs to
        // whoever appends to it.
        data.setJournal(readJournal(uuid, root.get("journal")), 0);
    }

    private EndExpedition readExpedition(UUID uuid, JsonObject section) {
        JsonObject origin = Json.objectOrNull(section, "origin");
        JsonObject returnPoint = Json.objectOrNull(section, "return");
        JsonObject end = Json.objectOrNull(section, "end");

        String lifeId = Json.stringOrNull(section, "lifeId");
        String originWorld = origin == null ? null : Json.stringOrNull(origin, "world");
        String returnWorld = returnPoint == null ? null : Json.stringOrNull(returnPoint, "world");
        String endWorld = end == null ? null : Json.stringOrNull(end, "world");

        if (lifeId == null || originWorld == null || returnWorld == null || endWorld == null) {
            logger.warning("Discarding incomplete End expedition record for " + uuid
                + " (missing life id or world names). The player will be offered a recovery return.");
            return null;
        }

        UUID parsedLifeId;
        try {
            parsedLifeId = UUID.fromString(lifeId);
        } catch (IllegalArgumentException e) {
            logger.warning("Discarding End expedition record for " + uuid
                + " with an unreadable life id '" + lifeId + "'.");
            return null;
        }

        return new EndExpedition(
            parsedLifeId,
            originWorld, Json.doubleOr(origin, "x", 0), Json.doubleOr(origin, "y", 0),
            Json.doubleOr(origin, "z", 0),
            returnWorld, Json.doubleOr(returnPoint, "x", 0), Json.doubleOr(returnPoint, "y", 0),
            Json.doubleOr(returnPoint, "z", 0),
            endWorld, Json.doubleOr(end, "x", 0), Json.doubleOr(end, "y", 0),
            Json.doubleOr(end, "z", 0),
            Json.stringOr(section, "region", ""),
            Json.longOr(section, "openedAt", 0L)
        );
    }

    /**
     * Reads the journal, dropping only the entries that cannot be understood. A history with a
     * damaged line in it is still a history: one bad entry never costs a player the rest.
     */
    private List<JournalEntry> readJournal(UUID uuid, JsonElement element) {
        List<JournalEntry> entries = new ArrayList<>();
        if (element == null || !element.isJsonArray()) return entries;

        int unreadable = 0;
        for (JsonElement raw : element.getAsJsonArray()) {
            if (!raw.isJsonObject()) {
                unreadable++;
                continue;
            }
            JsonObject entry = raw.getAsJsonObject();
            JournalEntry.Reason reason = JournalEntry.Reason.parse(Json.stringOrNull(entry, "reason"));
            Instant at = parseInstant(Json.stringOrNull(entry, "at"));
            if (reason == null || at == null) {
                unreadable++;
                continue;
            }
            entries.add(new JournalEntry(at, reason, Json.stringOr(entry, "player", ""),
                Json.stringOr(entry, "world", ""),
                Json.longOr(entry, "x", 0), Json.longOr(entry, "y", 0), Json.longOr(entry, "z", 0)));
        }

        if (unreadable > 0) {
            logger.warning("Dropped " + unreadable + " unreadable journal "
                + (unreadable == 1 ? "entry" : "entries") + " for " + uuid
                + "; the rest of their history is intact.");
        }
        return entries;
    }

    private static Instant parseInstant(String raw) {
        if (raw == null) return null;
        try {
            return Instant.parse(raw.trim());
        } catch (DateTimeException e) {
            return null;
        }
    }
}
