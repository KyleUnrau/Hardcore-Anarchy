package dev.unrau.samsara.data;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.UUID;

/**
 * An open End expedition — the record of where one particular journey into the End began.
 *
 * <p>This is deliberately a separate concept from both respawning and exile:
 *
 * <ul>
 *   <li>A bed or respawn anchor says where a player belongs during their present life.</li>
 *   <li>An exile destination says where a <em>new</em> life begins after death.</li>
 *   <li>An expedition says where <em>this journey</em> started, and therefore where a portal
 *       must return the traveller.</li>
 * </ul>
 *
 * <p>An expedition is bound to the life that opened it via {@link #getLifeId()}. Death rotates
 * the player's life id, which permanently invalidates any expedition opened by the dead life —
 * a new life is never returned to the old life's stronghold.
 *
 * <p>Immutable. Presence of an instance on {@link PlayerData} means the expedition is open;
 * closing it means dropping the reference.
 */
public final class EndExpedition {

    private final UUID lifeId;

    private final String originWorld;
    private final double originX, originY, originZ;

    private final String returnWorld;
    private final double returnX, returnY, returnZ;

    private final String endWorld;
    private final double endX, endY, endZ;

    private final String regionKey;
    private final long openedAt;

    public EndExpedition(UUID lifeId,
                         String originWorld, double originX, double originY, double originZ,
                         String returnWorld, double returnX, double returnY, double returnZ,
                         String endWorld, double endX, double endY, double endZ,
                         String regionKey, long openedAt) {
        this.lifeId = lifeId;
        this.originWorld = originWorld;
        this.originX = originX; this.originY = originY; this.originZ = originZ;
        this.returnWorld = returnWorld;
        this.returnX = returnX; this.returnY = returnY; this.returnZ = returnZ;
        this.endWorld = endWorld;
        this.endX = endX; this.endY = endY; this.endZ = endZ;
        this.regionKey = regionKey;
        this.openedAt = openedAt;
    }

    /**
     * Opens an expedition from live {@link Location}s.
     *
     * @param lifeId       the life that is making the journey
     * @param origin       the Overworld portal the player stepped into
     * @param returnPoint  a validated safe standing spot beside that portal
     * @param arrival      the regional End arrival point
     * @param regionKey    the End region identifier, for logging and admin inspection
     */
    public static EndExpedition open(UUID lifeId, Location origin, Location returnPoint,
                                     Location arrival, String regionKey) {
        return new EndExpedition(
            lifeId,
            origin.getWorld().getName(), origin.getX(), origin.getY(), origin.getZ(),
            returnPoint.getWorld().getName(), returnPoint.getX(), returnPoint.getY(), returnPoint.getZ(),
            arrival.getWorld().getName(), arrival.getX(), arrival.getY(), arrival.getZ(),
            regionKey,
            System.currentTimeMillis()
        );
    }

    /**
     * True if this expedition belongs to the given life. A null or mismatched life id means the
     * expedition is stale — it was opened by a life that has since died.
     */
    public boolean isValidFor(UUID currentLifeId) {
        return currentLifeId != null && currentLifeId.equals(lifeId);
    }

    public UUID getLifeId()       { return lifeId; }

    public String getOriginWorld() { return originWorld; }
    public double getOriginX()     { return originX; }
    public double getOriginY()     { return originY; }
    public double getOriginZ()     { return originZ; }
    public Location originLocation(World world) { return new Location(world, originX, originY, originZ); }

    public String getReturnWorld() { return returnWorld; }
    public double getReturnX()     { return returnX; }
    public double getReturnY()     { return returnY; }
    public double getReturnZ()     { return returnZ; }
    public Location returnLocation(World world) { return new Location(world, returnX, returnY, returnZ); }

    public String getEndWorld()    { return endWorld; }
    public double getEndX()        { return endX; }
    public double getEndY()        { return endY; }
    public double getEndZ()        { return endZ; }
    public Location arrivalLocation(World world) { return new Location(world, endX, endY, endZ); }

    public String getRegionKey()   { return regionKey; }
    public long getOpenedAt()      { return openedAt; }

    @Override
    public String toString() {
        return String.format("EndExpedition{life=%s, origin=%s %d/%d/%d, return=%s %d/%d/%d, end=%s %d/%d/%d, region=%s}",
            lifeId, originWorld, (long) originX, (long) originY, (long) originZ,
            returnWorld, (long) returnX, (long) returnY, (long) returnZ,
            endWorld, (long) endX, (long) endY, (long) endZ, regionKey);
    }
}
