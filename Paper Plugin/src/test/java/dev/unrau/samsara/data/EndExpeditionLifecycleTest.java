package dev.unrau.samsara.data;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule this file exists to protect: End travel preserves the current life and returns the
 * traveller to the journey's origin — but a death ends the life, and the journey with it.
 */
class EndExpeditionLifecycleTest {

    private static EndExpedition expeditionFor(UUID lifeId) {
        return new EndExpedition(
            lifeId,
            "world", 120_000, 28, -64_000,
            "world", 122, 29, -63_998,
            "world_the_end", 7552, 65, -4096,
            "7552,-4096",
            1_700_000_000_000L
        );
    }

    @Test
    void anOpenExpeditionBelongsToTheLifeThatStartedIt() {
        PlayerData data = new PlayerData();
        UUID lifeId = data.ensureLifeId();
        data.openEndExpedition(expeditionFor(lifeId));

        assertNotNull(data.getActiveExpedition());
        assertEquals("7552,-4096", data.getActiveExpedition().getRegionKey());
    }

    @Test
    void deathEndsTheLifeAndTheExpeditionWithIt() {
        PlayerData data = new PlayerData();
        UUID firstLife = data.ensureLifeId();
        data.openEndExpedition(expeditionFor(firstLife));

        data.beginNewLife();

        assertNull(data.getActiveExpedition(), "a new life must never inherit the old life's way home");
        assertNull(data.getEndExpedition(), "the record itself is closed, not merely hidden");
        assertNotEquals(firstLife, data.getLifeId());
    }

    @Test
    void anExpeditionLeftBehindByADeadLifeIsNeverUsable() {
        // Simulates a stale record surviving on disk: the file still holds an expedition, but the
        // life id moved on. Loading it must not resurrect the old return destination.
        PlayerData data = new PlayerData();
        UUID deadLife = UUID.randomUUID();
        data.setLifeId(UUID.randomUUID());
        data.setEndExpedition(expeditionFor(deadLife));

        assertNull(data.getActiveExpedition());
        assertNotNull(data.getEndExpedition(), "the raw record is still visible to admin tooling");
        assertFalse(data.getEndExpedition().isValidFor(data.getLifeId()));
    }

    @Test
    void anExpeditionIsInvalidForAPlayerWithNoLifeIdentity() {
        EndExpedition expedition = expeditionFor(UUID.randomUUID());

        assertFalse(expedition.isValidFor(null));
    }

    @Test
    void respawnAndExileStateDoNotMoveTheReturnDestination() {
        // A player's bed being destroyed, or their respawn data changing while they are away,
        // must leave the way home exactly where it was.
        PlayerData data = new PlayerData();
        UUID lifeId = data.ensureLifeId();
        data.openEndExpedition(expeditionFor(lifeId));

        data.setPendingRespawn("world", 999_999, 70, 999_999);
        data.setLastExile("world", -500_000, 63, 250_000);
        data.setFirstSpawn("world", 12, 64, 34);

        EndExpedition unchanged = data.getActiveExpedition();
        assertNotNull(unchanged);
        assertEquals(122, unchanged.getReturnX());
        assertEquals(29, unchanged.getReturnY());
        assertEquals(-63_998, unchanged.getReturnZ());
    }

    @Test
    void closingAnExpeditionLeavesTheLifeIntact() {
        PlayerData data = new PlayerData();
        UUID lifeId = data.ensureLifeId();
        data.openEndExpedition(expeditionFor(lifeId));

        data.closeEndExpedition();

        assertNull(data.getActiveExpedition());
        assertEquals(lifeId, data.getLifeId(), "coming home is not a death");
    }

    @Test
    void aLifeIdentityIsGeneratedOnceAndThenReused() {
        PlayerData data = new PlayerData();

        assertNull(data.getLifeId());
        UUID first = data.ensureLifeId();
        UUID second = data.ensureLifeId();

        assertEquals(first, second);
        assertTrue(expeditionFor(first).isValidFor(second));
    }
}
