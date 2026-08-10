package dev.unrau.samsara.log;

import dev.unrau.samsara.config.PluginConfig;
import dev.unrau.samsara.data.JournalEntry;
import dev.unrau.samsara.data.PlayerData;
import dev.unrau.samsara.data.PlayerDataStore;

import java.util.UUID;

/**
 * Writes what happened to a player onto that player's own record.
 *
 * <p>This replaces the server-wide {@code exile-log.csv}. A history that belongs to one player is
 * kept with everything else that belongs to that player: reading it means opening one file, and
 * deleting a player deletes their history with them. Nothing has to be filtered out of a shared
 * file that grows for as long as the server runs.
 *
 * <p>Every entry is a read-modify-write of the player's file, which is safe because every caller
 * is on the main server thread — the same thread that has already saved whatever state the event
 * changed, so the record this reads back is the current one.
 */
public class PlayerJournal {

    private final PlayerDataStore store;
    private final PluginConfig config;

    public PlayerJournal(PlayerDataStore store, PluginConfig config) {
        this.store = store;
        this.config = config;
    }

    /**
     * Records an event against a player. Settings are read per call, so {@code /samsara reload} is
     * enough to turn the journal on or off, or to change how much of it is kept.
     */
    public void record(JournalEntry.Reason reason, UUID uuid, String playerName,
                       String world, double x, double y, double z) {
        if (!config.isJournalEnabled()) return;

        PlayerData data = store.load(uuid);
        data.addJournalEntry(
            JournalEntry.now(reason, playerName, world, x, y, z),
            config.getJournalMaxEntries());
        store.save(uuid, data);
    }
}
