package dev.unrau.samsara.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class PlayerData {

    /**
     * Schema version written by {@link PlayerDataStore}. Bumped when the on-disk layout changes.
     *
     * <p>4 is the first JSON version, and the first to carry the player's {@code journal} — the
     * history that used to be appended to a single server-wide {@code exile-log.csv}.
     */
    public static final int CURRENT_DATA_VERSION = 4;

    private int dataVersion = CURRENT_DATA_VERSION;

    private boolean hasJoinedBefore = false;

    /**
     * Identity of the world this record describes. A regenerated world gets a new UID, which is how
     * a stale playerdata file left over from a previous world is recognised: without this the plugin
     * believes the player has already been placed and vanilla drops them at world spawn.
     * Null for records written before this field existed.
     */
    private UUID worldUid;

    /**
     * Identity of the player's current life. Rotated on death, which is what invalidates any End
     * expedition the dead life had open. Null for legacy data until a life-scoped feature needs it.
     */
    private UUID lifeId;

    /** The player's open End expedition, or null when they have none. */
    private EndExpedition endExpedition;

    /**
     * What has happened to this player, oldest first. History, not state: nothing the plugin does
     * is decided by reading it.
     */
    private final List<JournalEntry> journal = new ArrayList<>();

    private String firstSpawnWorld;
    private double firstSpawnX, firstSpawnY, firstSpawnZ;

    private String lastDeathWorld;
    private double lastDeathX, lastDeathY, lastDeathZ;

    private String pendingRespawnWorld;
    private double pendingRespawnX, pendingRespawnY, pendingRespawnZ;
    private boolean hasPendingRespawn = false;

    private String lastExileWorld;
    private double lastExileX, lastExileY, lastExileZ;

    private int deathCount = 0;

    /** True while the async exile location search is running. */
    private boolean calculatingRespawn = false;

    /** True if the player clicked Respawn before the async search completed. */
    private boolean needsDelayedTeleport = false;

    public int getDataVersion() { return dataVersion; }
    public void setDataVersion(int v) { dataVersion = v; }

    public boolean isHasJoinedBefore() { return hasJoinedBefore; }
    public void setHasJoinedBefore(boolean v) { hasJoinedBefore = v; }

    public UUID getWorldUid() { return worldUid; }
    public void setWorldUid(UUID v) { worldUid = v; }

    /**
     * True if this record cannot be trusted to describe a player who is already placed in the given
     * world — either it was written for a different world, or it claims a previous join while
     * holding no position at all. Either way the honest response is a fresh exile.
     *
     * <p>Records with no world id are from an older version and are given the benefit of the doubt.
     */
    public boolean isStaleFor(UUID currentWorldUid) {
        if (!hasJoinedBefore) return false;
        if (worldUid != null && currentWorldUid != null && !worldUid.equals(currentWorldUid)) return true;
        return firstSpawnWorld == null && lastExileWorld == null && !hasPendingRespawn;
    }

    public UUID getLifeId() { return lifeId; }
    public void setLifeId(UUID v) { lifeId = v; }

    /** Returns the current life id, generating one first if this player has never needed one. */
    public UUID ensureLifeId() {
        if (lifeId == null) {
            lifeId = UUID.randomUUID();
        }
        return lifeId;
    }

    /**
     * Ends the current life and begins a new one. Any open End expedition belonged to the life that
     * just died, so it is closed here — a new life is never returned to the old life's stronghold.
     */
    public void beginNewLife() {
        lifeId = UUID.randomUUID();
        endExpedition = null;
    }

    /**
     * Discards everything that described a position in a world this record no longer belongs to,
     * leaving the death count and the journal intact — those things happened, whatever the map
     * looked like at the time.
     */
    public void resetForNewWorld(UUID newWorldUid) {
        worldUid = newWorldUid;
        hasJoinedBefore = false;
        firstSpawnWorld = null;
        lastDeathWorld = null;
        lastExileWorld = null;
        hasPendingRespawn = false;
        calculatingRespawn = false;
        needsDelayedTeleport = false;
        endExpedition = null;
        lifeId = UUID.randomUUID();
    }

    /** The raw stored expedition, including one left behind by a dead life. */
    public EndExpedition getEndExpedition() { return endExpedition; }
    public void setEndExpedition(EndExpedition v) { endExpedition = v; }

    /** The open expedition for the player's <em>current</em> life, or null if there is none. */
    public EndExpedition getActiveExpedition() {
        if (endExpedition == null) return null;
        return endExpedition.isValidFor(lifeId) ? endExpedition : null;
    }

    public void openEndExpedition(EndExpedition expedition) { endExpedition = expedition; }
    public void closeEndExpedition() { endExpedition = null; }

    /** This player's history, oldest first. Unmodifiable; append through {@link #addJournalEntry}. */
    public List<JournalEntry> getJournal() { return Collections.unmodifiableList(journal); }

    /** The most recent entry, or null for a player nothing has happened to yet. */
    public JournalEntry getLatestJournalEntry() {
        return journal.isEmpty() ? null : journal.get(journal.size() - 1);
    }

    /**
     * Appends an entry and drops the oldest ones past the limit, so a long-lived player's file
     * stops growing rather than growing forever.
     *
     * @param maxEntries how many entries to keep; zero or less keeps all of them
     */
    public void addJournalEntry(JournalEntry entry, int maxEntries) {
        journal.add(entry);
        trimJournal(maxEntries);
    }

    /** Replaces the whole journal, used by the loader and by the one-off CSV import. */
    public void setJournal(List<JournalEntry> entries, int maxEntries) {
        journal.clear();
        journal.addAll(entries);
        trimJournal(maxEntries);
    }

    private void trimJournal(int maxEntries) {
        if (maxEntries <= 0) return;
        while (journal.size() > maxEntries) {
            journal.remove(0);
        }
    }

    public String getFirstSpawnWorld() { return firstSpawnWorld; }
    public double getFirstSpawnX() { return firstSpawnX; }
    public double getFirstSpawnY() { return firstSpawnY; }
    public double getFirstSpawnZ() { return firstSpawnZ; }
    public void setFirstSpawn(String world, double x, double y, double z) {
        firstSpawnWorld = world; firstSpawnX = x; firstSpawnY = y; firstSpawnZ = z;
    }

    public String getLastDeathWorld() { return lastDeathWorld; }
    public double getLastDeathX() { return lastDeathX; }
    public double getLastDeathY() { return lastDeathY; }
    public double getLastDeathZ() { return lastDeathZ; }
    public void setLastDeath(String world, double x, double y, double z) {
        lastDeathWorld = world; lastDeathX = x; lastDeathY = y; lastDeathZ = z;
    }

    public boolean isHasPendingRespawn() { return hasPendingRespawn; }
    public String getPendingRespawnWorld() { return pendingRespawnWorld; }
    public double getPendingRespawnX() { return pendingRespawnX; }
    public double getPendingRespawnY() { return pendingRespawnY; }
    public double getPendingRespawnZ() { return pendingRespawnZ; }
    public void setPendingRespawn(String world, double x, double y, double z) {
        pendingRespawnWorld = world; pendingRespawnX = x; pendingRespawnY = y; pendingRespawnZ = z;
        hasPendingRespawn = true;
    }
    public void clearPendingRespawn() { hasPendingRespawn = false; }

    public String getLastExileWorld() { return lastExileWorld; }
    public double getLastExileX() { return lastExileX; }
    public double getLastExileY() { return lastExileY; }
    public double getLastExileZ() { return lastExileZ; }
    public void setLastExile(String world, double x, double y, double z) {
        lastExileWorld = world; lastExileX = x; lastExileY = y; lastExileZ = z;
    }

    public int getDeathCount() { return deathCount; }
    public void incrementDeathCount() { deathCount++; }

    public boolean isCalculatingRespawn() { return calculatingRespawn; }
    public void setCalculatingRespawn(boolean v) { calculatingRespawn = v; }

    public boolean isNeedsDelayedTeleport() { return needsDelayedTeleport; }
    public void setNeedsDelayedTeleport(boolean v) { needsDelayedTeleport = v; }
}
