package dev.unrau.hardcoreanarchy.data;

public class PlayerData {

    private boolean hasJoinedBefore = false;

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

    public boolean isHasJoinedBefore() { return hasJoinedBefore; }
    public void setHasJoinedBefore(boolean v) { hasJoinedBefore = v; }

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
