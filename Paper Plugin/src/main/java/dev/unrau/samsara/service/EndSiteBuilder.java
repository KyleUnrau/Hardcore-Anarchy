package dev.unrau.samsara.service;

import dev.unrau.samsara.config.DimensionalTravelConfig;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.EndGateway;
import org.bukkit.block.TileState;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Builds and maintains the plugin's two kinds of End site.
 *
 * <p>They look nothing alike, and that is the point — a traveller should be able to tell at a glance
 * whether the door in front of them leads out of the End or merely across it.
 *
 * <ul>
 *   <li>A <b>home site</b> stands at the reflection of an Overworld End portal. It is a built thing:
 *       a 7×7 obsidian-rimmed terrace of end stone brick with crying obsidian at its four corners and
 *       at its centre, a brick block and a stair marking each corner, and a bedrock-framed doorway
 *       two blocks tall standing a step above the floor at the back, approached between two brick
 *       posts capped with end stone brick wall. Deliberately artificial, because somebody made it —
 *       a player lit an End portal on the other side, and this is the door that opened.</li>
 *   <li>A <b>wormhole</b> is a vanilla End gateway and nothing else: the same twelve blocks of
 *       bedrock around a single gateway block that the dragon leaves behind, floating
 *       {@code wormholes.gatewayHeight} blocks above the ground. Sealed above and below, open on all
 *       four sides — you get in by throwing a pearl or standing on a trapdoor, exactly as in vanilla.
 *       Where there is no ground beneath it, an End island is grown the way vanilla grows one for a
 *       gateway whose destination is empty.</li>
 * </ul>
 *
 * <p>Three rules keep this safe to run against an existing world:
 *
 * <ul>
 *   <li><b>Nothing natural is destroyed.</b> Only air and end stone are ever replaced. If an end
 *       city, chorus forest, gateway or player build occupies the footprint, the structure is raised
 *       above it instead of cutting through it. The bedrock frame is the one exception: it is always
 *       restored, so a door cannot be built over.</li>
 *   <li><b>Building is idempotent.</b> Every visit re-runs the same build, which repairs damage from
 *       griefing or explosions without duplicating anything, and which is why the network survives a
 *       restart without any of it being written to disk. Two things are needed for that to be true of
 *       a site's <em>height</em> and not merely of its blocks. The island's shape is drawn from a
 *       random seeded on its own coordinates, so a second visit redraws the same island rather than
 *       growing a new one on top of it. And a site that already stands is rebuilt at the height of
 *       the gateway standing there rather than at a height derived from the terrain all over again —
 *       see {@link SiteAnchor} for why a recomputed height stacked wormholes on top of each
 *       other.</li>
 *   <li><b>Identity is persistent.</b> Each gateway carries the plugin's tag, its kind, and the site
 *       centre it belongs to, in the block's persistent data. That is what distinguishes a way home
 *       from a wormhole, and it rides along in the chunk across restarts.</li>
 * </ul>
 */
public class EndSiteBuilder {

    /** Blocks the builder is allowed to overwrite. Everything else is treated as worth keeping. */
    private static final Set<Material> REPLACEABLE = Set.of(
        Material.AIR,
        Material.CAVE_AIR,
        Material.VOID_AIR,
        Material.END_STONE,
        Material.END_STONE_BRICKS
    );

    /**
     * The builder's own structural blocks.
     *
     * <p>Never overwritten by the platform pass, but never treated as an obstruction either. Without
     * this second half a site would climb on every single visit: the bedrock frame it placed last
     * time reads as something worth building above, and the floor is raised to clear it.
     */
    private static final Set<Material> STRUCTURAL = Set.of(
        Material.BEDROCK,
        Material.END_GATEWAY
    );

    /**
     * The builder's own masonry: the platform floor and everything decorative standing on it.
     *
     * <p>Unlike {@link #STRUCTURAL} these <em>are</em> freely overwritten, and that is the point —
     * every visit lays the terrace down again, so a corner somebody blew up comes back. They are also
     * invisible to the terrain reads for the same reason the bedrock is: without that, last visit's
     * obsidian rim would read as ground worth building on top of and the site would climb a block
     * every time anyone walked through it.
     */
    private static final Set<Material> MASONRY = Set.of(
        Material.END_STONE_BRICKS,
        Material.END_STONE_BRICK_STAIRS,
        Material.END_STONE_BRICK_WALL,
        Material.OBSIDIAN,
        Material.CRYING_OBSIDIAN
    );

    /** Height of a home doorway, in blocks. Two so a player can walk straight through it. */
    private static final int GATEWAY_HEIGHT = 2;

    /** How far the doorway's threshold stands above the platform floor. One step up, by design. */
    private static final int DOORWAY_LIFT = 1;

    /**
     * Height of the lowest block of a home doorway above the platform floor.
     *
     * <p>Read backwards, this is what recovers a standing platform's floor from the door in it, which
     * is how a rebuild finds the height it is meant to use rather than deriving a new one.
     */
    private static final int DOORWAY_BASE = DOORWAY_LIFT + 1;

    /** Bedrock capping the doorway opening. A single course: the frame closes the door, not looms. */
    private static final int LINTEL_HEIGHT = 1;

    /** Height of the end stone brick wall wrapping each corner of the terrace. One course only. */
    private static final int CORNER_WALL_HEIGHT = 1;

    /**
     * Blocks of a rim one corner takes up: the corner block itself, then the stair stepping down from
     * it. Two corners leave {@code 2 × radius + 1 - 2 × CORNER_WALL_REACH} blocks open in the middle
     * of every rim — three of them at the default radius, which is the gap a traveller walks out
     * through.
     */
    private static final int CORNER_WALL_REACH = 2;

    /** Top of the doorway frame, in blocks above the platform floor. */
    private static final int FRAME_TOP = DOORWAY_LIFT + GATEWAY_HEIGHT + LINTEL_HEIGHT;

    /** Distance from the doorway's centre line to its bedrock jambs: a frame three blocks wide. */
    private static final int FRAME_RADIUS = 1;

    /** Height of clear space kept above the home platform floor. Must exceed the gateway frame. */
    private static final int HEADROOM = FRAME_TOP + 1;

    /** Half-width of the vanilla gateway shell: a 3×5×3 box around the gateway block. */
    private static final int SHELL_RADIUS = 1;

    /** Vertical half-height of that shell — bedrock caps sit two blocks above and below. */
    private static final int SHELL_HEIGHT = 2;

    /**
     * Widest an End island can grow, in blocks from its centre.
     *
     * <p>Vanilla starts the island at a radius of 4 to 6 and tests {@code dx² + dz² <= (r + 1)²}, so
     * the furthest block it can place is six out. Callers load chunks by this, not by guesswork.
     */
    public static final int ISLAND_RADIUS = 6;

    private final JavaPlugin plugin;
    private final NamespacedKey gatewayKey;
    private final NamespacedKey kindKey;
    private final NamespacedKey siteXKey;
    private final NamespacedKey siteZKey;

    public EndSiteBuilder(JavaPlugin plugin) {
        this.plugin = plugin;
        // Kept as the original key name so sites built by earlier versions are still recognised.
        this.gatewayKey = new NamespacedKey(plugin, "return_gateway");
        this.kindKey = new NamespacedKey(plugin, "gateway_kind");
        this.siteXKey = new NamespacedKey(plugin, "site_x");
        this.siteZKey = new NamespacedKey(plugin, "site_z");
    }

    /**
     * Ensures a usable site exists at the given position and returns the point a traveller should
     * land on. Must be called on the main thread with the surrounding chunks already loaded.
     *
     * @param kind  which kind of site this is. The two are entirely different structures; see the
     *              class documentation
     * @param build when false, no blocks are placed; an existing natural surface is used if there is
     *              one, otherwise null is returned and the caller decides what to do instead
     * @return the standing position at the site centre, or null if nothing could be offered
     */
    public Location ensureSite(World world, Coord site, DimensionalTravelConfig settings,
                               boolean build, SiteKind kind) {
        return kind == SiteKind.HOME
            ? ensureHomeSite(world, site, settings, build)
            : ensureWormhole(world, site, settings, build);
    }

    /** How far around a site the caller must load chunks for the widest thing built there. */
    public static int footprintRadius(DimensionalTravelConfig settings) {
        return Math.max(settings.getArrivalPlatformRadius(), ISLAND_RADIUS) + 2;
    }

    /**
     * What this gateway is for, or null if the plugin has never taken responsibility for it.
     *
     * <p>A null answer means a gateway nobody has adopted yet, and the caller treats it as a
     * wormhole — which is what {@link #adopt} will shortly record on it.
     */
    public SiteKind kindOf(Block block) {
        if (block.getType() != Material.END_GATEWAY) return null;
        if (!(block.getState() instanceof TileState tile)) return null;
        return kindOf(tile);
    }

    /** As {@link #kindOf(Block)}, for a state already in hand. */
    public SiteKind kindOf(TileState tile) {
        var container = tile.getPersistentDataContainer();
        if (!container.has(gatewayKey, PersistentDataType.BYTE)) return null;
        return SiteKind.fromTag(container.get(kindKey, PersistentDataType.STRING));
    }

    /** True if this gateway carries the plugin's tag, whether it was built by us or adopted. */
    public boolean isOurs(Block block) {
        return kindOf(block) != null;
    }

    /**
     * Takes responsibility for a gateway the world generated — the ring the dragon leaves behind, or
     * anything an older world already had standing.
     *
     * <p>Two things happen, and both matter.
     *
     * <p>The gateway is <b>labelled a wormhole</b> belonging to the centre of its pairing cell, which
     * is what makes it route through {@link WormholePairing} like every other wormhole rather than
     * being read as a way home.
     *
     * <p>It is also <b>given an exit</b>, pointing at itself. Vanilla resolves a gateway's
     * destination the first time anything enters it, and that resolution runs before any plugin gets
     * a say: it hunts down an outer island, generates one if the region is empty, and builds a return
     * gateway there. Filling the exit in ahead of time is what stops all of that — the plugin
     * performs the real teleport, and the only thing vanilla is left holding is a hop to where the
     * traveller already stands.
     *
     * @return true if this call adopted the gateway, false if it was already ours
     */
    public boolean adopt(EndGateway gateway, Coord site) {
        if (kindOf(gateway) != null) return false;

        Block block = gateway.getBlock();
        gateway.setExitLocation(new Location(block.getWorld(),
            block.getX() + 0.5, block.getY(), block.getZ() + 0.5));
        gateway.setExactTeleport(true);
        tag(gateway, site, SiteKind.WORMHOLE);
        gateway.update(true, false);
        return true;
    }

    /**
     * The centre of the site a gateway belongs to.
     *
     * <p>A gateway rarely stands at the coordinate its destination is computed from — a home gateway
     * sits on the platform rim, and a wormhole's pairing is a property of its cell — so routing from
     * the block's own position would land a traveller a few blocks off. Recording the centre keeps
     * both the reflection and the pairing exact.
     *
     * @return the site centre, or the gateway's own position for anything that does not record one
     */
    public Coord siteOriginOf(Block block) {
        if (block.getState() instanceof TileState tile) {
            var container = tile.getPersistentDataContainer();
            Integer x = container.get(siteXKey, PersistentDataType.INTEGER);
            Integer z = container.get(siteZKey, PersistentDataType.INTEGER);
            if (x != null && z != null) {
                return new Coord(x, z);
            }
        }
        return new Coord(block.getX(), block.getZ());
    }

    // -------------------------------------------------------------------------
    // Home sites: the way out of the End
    // -------------------------------------------------------------------------

    private Location ensureHomeSite(World world, Coord site, DimensionalTravelConfig settings,
                                    boolean build) {
        int centreX = site.x();
        int centreZ = site.z();
        int radius = settings.getArrivalPlatformRadius();

        int floorY = homeFloorY(world, site, radius, settings);

        if (!build) {
            // Construction disabled: only offer this site if the terrain is already safe as it is.
            if (isNaturallyLandable(world, centreX, floorY + 1, centreZ)) {
                return new Location(world, centreX + 0.5, floorY + 1, centreZ + 0.5);
            }
            return null;
        }

        // Order matters: the floor pass clears the air above it, so anything standing on the terrace
        // has to be raised afterwards or it would be swept away the moment it was placed.
        buildPlatform(world, centreX, floorY, centreZ, radius);
        raiseCornerWalls(world, centreX, floorY, centreZ, radius);
        placeDoorway(world, site, floorY, radius);

        return new Location(world, centreX + 0.5, floorY + 1, centreZ + 0.5);
    }

    /**
     * The floor a home platform is rebuilt on.
     *
     * <p>A platform that already stands keeps the floor it was built on, recovered from its own
     * doorway. Only a site that does not exist yet asks the terrain, and it asks once.
     *
     * <p>That is not a micro-optimisation, it is the fix for a platform that climbed. The terrain
     * read raises the floor above anything in the footprint the builder will not overwrite, and a
     * platform is somewhere players stand: the first chest, torch or block anybody puts down there
     * would push the next rebuild a block higher, and because the bedrock doorway is force-placed and
     * never removed, the old one would stay behind under the new one. See {@link SiteAnchor}.
     */
    private int homeFloorY(World world, Coord site, int radius, DimensionalTravelConfig settings) {
        List<Integer> blocks = ourGatewayColumn(world, site.x(), site.z() - radius, site, SiteKind.HOME);
        if (blocks.isEmpty()) {
            return choosePlatformY(world, site.x(), site.z(), radius, settings);
        }

        // A doorway is two blocks tall, so the blocks of one are not duplicates of each other: only
        // the bottom of a run of them names a door.
        List<Integer> doors = blocks.stream().filter(y -> !blocks.contains(y - 1)).toList();

        SiteAnchor anchor = SiteAnchor.of(doors);
        if (!anchor.strays().isEmpty()) {
            // Left where they are on purpose. A stray wormhole shell is twelve blocks of bedrock in
            // the void; a stray doorway stands on a terrace players may have built around, and
            // pulling bedrock out from under somebody's base unasked is worse than an odd-looking
            // door. The platform cannot grow another one from here.
            plugin.getLogger().warning("[Travel] Home site " + site.key() + " has " + anchor.strays().size()
                + " leftover doorway(s) at y " + anchor.strays() + " from an older build; using the one at y "
                + anchor.y() + " and leaving the rest standing. They can be removed by hand.");
        }
        return anchor.y() - DOORWAY_BASE;
    }

    /**
     * Picks the floor level for the platform: on top of natural End terrain where there is any,
     * otherwise at the configured void height. If anything worth preserving stands in the footprint,
     * the floor is raised above it so the build never cuts into it.
     */
    private int choosePlatformY(World world, int centreX, int centreZ, int radius,
                                DimensionalTravelConfig settings) {
        int minY = minBuildY(world);
        int maxY = Math.min(world.getMaxHeight() - HEADROOM - 3, 240);

        int surfaceY = highestSolidY(world, centreX, centreZ, minY, maxY);
        int floorY = surfaceY > Integer.MIN_VALUE ? surfaceY : settings.getArrivalPlatformY();

        // Raise above anything we refuse to overwrite anywhere in the build box, including the
        // doorway's frame columns, so a site next to an end city floats clear rather than carving in.
        int protectedTop = highestProtectedY(world, centreX, centreZ, radius + 1, minY, maxY);
        if (protectedTop > Integer.MIN_VALUE && protectedTop >= floorY) {
            floorY = protectedTop + 1;
        }

        return Math.max(minY, Math.min(floorY, maxY));
    }

    /**
     * Lays the terrace: end stone brick underfoot, an obsidian rim around it, and crying obsidian at
     * the four corners and at the very centre — the one block a traveller always lands on, so it is
     * the one block that is unmistakable from anywhere on the platform.
     *
     * <p>Also clears the headroom above, which is why this runs before anything is stood on it.
     */
    private void buildPlatform(World world, int centreX, int floorY, int centreZ, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                setIfOurs(world, centreX + dx, floorY, centreZ + dz, floorMaterial(dx, dz, radius));

                for (int dy = 1; dy <= HEADROOM; dy++) {
                    clearIfOurs(world, centreX + dx, floorY + dy, centreZ + dz);
                }
            }
        }
    }

    private Material floorMaterial(int dx, int dz, int radius) {
        boolean onRimX = Math.abs(dx) == radius;
        boolean onRimZ = Math.abs(dz) == radius;

        if (dx == 0 && dz == 0) return Material.CRYING_OBSIDIAN;
        if (onRimX && onRimZ) return Material.CRYING_OBSIDIAN;
        if (onRimX || onRimZ) return Material.OBSIDIAN;
        return Material.END_STONE_BRICKS;
    }

    /**
     * Marks each corner of the terrace with a single end stone brick block one course high, and a
     * stair immediately beside it on each of the two rims it meets, stepping back down to the rim.
     *
     * <p>It carries no weight — nothing here does — but it gives the platform an outline against the
     * void, which a flat disc of blocks does not have. A traveller coming out of the doorway can see
     * where the floor ends before they walk off it, and it stops there: each rim keeps three blocks
     * open in the middle to walk out through rather than climb over.
     *
     * <p>Where a corner or its stair would land on the doorway's bedrock the block is simply left
     * alone — bedrock is not ours to overwrite — so the corner stops short of the frame instead of
     * fighting it.
     */
    private void raiseCornerWalls(World world, int centreX, int floorY, int centreZ, int radius) {
        // Any narrower and the two corners of a rim would be reaching for the same block from
        // opposite sides, leaving nothing open between them.
        if (radius < CORNER_WALL_REACH) return;

        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                int cornerX = centreX + sx * radius;
                int cornerZ = centreZ + sz * radius;

                for (int dy = 1; dy <= CORNER_WALL_HEIGHT; dy++) {
                    setIfOurs(world, cornerX, floorY + dy, cornerZ, Material.END_STONE_BRICKS);
                }

                // A stair on each rim, its tall side against the corner block, so the corner reads
                // as the high point and the rim falls away from it.
                placeStairs(world, centreX + sx * (radius - 1), floorY + 1, cornerZ,
                    sx > 0 ? BlockFace.EAST : BlockFace.WEST);
                placeStairs(world, cornerX, floorY + 1, centreZ + sz * (radius - 1),
                    sz > 0 ? BlockFace.SOUTH : BlockFace.NORTH);
            }
        }
    }

    /**
     * Places the way home on the {@code -Z} rim: an opening {@value #GATEWAY_HEIGHT} blocks tall
     * standing {@value #DOORWAY_LIFT} block above the floor, so a traveller steps up into it rather
     * than walking flat off the terrace — a threshold, which is what the door deserves.
     *
     * <p>The frame is bedrock — unbreakable, so a door cannot be mined out from under somebody — a
     * solid face three blocks wide with the opening cut out of it, backed by a second face of the
     * same size so the whole thing reads as a block of bedrock from behind and nobody steps into the
     * void. In front of it stands the portico: a stair up to the sill with a post either side.
     */
    private void placeDoorway(World world, Coord site, int floorY, int radius) {
        int centreX = site.x();
        int centreZ = site.z();
        int gatewayZ = centreZ - radius;
        int backZ = gatewayZ - 1;
        int sillY = floorY + DOORWAY_LIFT;
        Location exit = new Location(world, centreX + 0.5, floorY + 1, centreZ + 0.5);

        // Bedrock all the way from the floor to the top of the lintel: two jambs, a sill under the
        // opening, and the lintel above it. Forced rather than placed politely, so the frame is whole
        // again however badly the last traveller treated it.
        for (int dy = 0; dy <= FRAME_TOP; dy++) {
            force(world, centreX - FRAME_RADIUS, floorY + dy, gatewayZ, Material.BEDROCK);
            force(world, centreX + FRAME_RADIUS, floorY + dy, gatewayZ, Material.BEDROCK);
        }
        for (int dy = 0; dy <= DOORWAY_LIFT; dy++) {
            force(world, centreX, floorY + dy, gatewayZ, Material.BEDROCK);
        }
        for (int dy = DOORWAY_LIFT + GATEWAY_HEIGHT + 1; dy <= FRAME_TOP; dy++) {
            force(world, centreX, floorY + dy, gatewayZ, Material.BEDROCK);
        }

        // The back wall, as wide and as tall as the frame in front of it. Without it the doorway is
        // a hole in the rim onto nothing; at full width the door reads from outside as a solid mass
        // of bedrock rather than a slab standing on its edge.
        for (int dx = -FRAME_RADIUS; dx <= FRAME_RADIUS; dx++) {
            for (int dy = 0; dy <= FRAME_TOP; dy++) {
                force(world, centreX + dx, floorY + dy, backZ, Material.BEDROCK);
            }
        }

        placePortico(world, centreX, floorY, gatewayZ, radius);

        // Each block is placed independently, so a site built by an earlier version — with a single
        // gateway block a player could not walk into — grows its second block on the next visit.
        for (int dy = 1; dy <= GATEWAY_HEIGHT; dy++) {
            placeGatewayBlock(world, centreX, sillY + dy, gatewayZ, site, exit, SiteKind.HOME);
        }
    }

    /**
     * The portico: the course of the terrace standing directly in front of the bedrock face.
     *
     * <p>An end stone brick stair on the centre line makes the step up onto the sill — cut into the
     * terrace rather than piled on top of it — and either side of it a brick block capped with an end
     * stone brick wall stands as a post, framing the approach so the doorway is read as an entrance
     * from anywhere on the platform rather than as a gap in the rim.
     *
     * <p>The wall caps are placed without physics, so each one stays a free-standing post instead of
     * reaching sideways for the bedrock behind it.
     *
     * <p>Skipped on a platform too narrow to hold a row in front of the frame at all.
     */
    private void placePortico(World world, int centreX, int floorY, int gatewayZ, int radius) {
        if (radius < 2) return;

        int porchZ = gatewayZ + 1;
        placeStairs(world, centreX, floorY + 1, porchZ, BlockFace.NORTH);

        for (int sx = -1; sx <= 1; sx += 2) {
            int x = centreX + sx * FRAME_RADIUS;
            setIfOurs(world, x, floorY + 1, porchZ, Material.END_STONE_BRICKS);
            setIfOurs(world, x, floorY + 2, porchZ, Material.END_STONE_BRICK_WALL);
        }
    }

    // -------------------------------------------------------------------------
    // Wormholes: a vanilla End gateway, and nothing more
    // -------------------------------------------------------------------------

    /**
     * Ensures a wormhole stands here and returns the ground beneath it.
     *
     * <p>The gateway itself is sealed above and below, so the landing spot is not the gateway but the
     * island under it — which is also where vanilla sets a traveller down when they come out of one.
     * Getting back in is the player's problem, and pearling up into a gateway is a thing every End
     * traveller already knows how to do.
     */
    private Location ensureWormhole(World world, Coord site, DimensionalTravelConfig settings,
                                    boolean build) {
        int centreX = site.x();
        int centreZ = site.z();
        int minY = minBuildY(world);
        int maxY = Math.min(world.getMaxHeight() - SHELL_HEIGHT - 2, 240);

        int surfaceY = highestSolidY(world, centreX, centreZ, minY, maxY);

        if (!build) {
            // Construction disabled: offer this site only if there is already ground to stand on.
            if (surfaceY > Integer.MIN_VALUE && isNaturallyLandable(world, centreX, surfaceY + 1, centreZ)) {
                return new Location(world, centreX + 0.5, surfaceY + 1, centreZ + 0.5);
            }
            return null;
        }

        int groundY = surfaceY;
        if (groundY == Integer.MIN_VALUE) {
            // Nothing here but void. Vanilla answers this by growing an island, and so do we.
            groundY = Math.max(minY, Math.min(settings.getArrivalPlatformY(), maxY));
            growEndIsland(world, site, groundY);
        }

        // A wormhole stands where it already stands. Only an empty column gets a height from the
        // terrain, and only once — recomputing it on every visit is what stacked shells on top of
        // each other, since the last one's bedrock is never taken down by the new one.
        SiteAnchor anchor = SiteAnchor.of(
            ourGatewayColumn(world, centreX, centreZ, site, SiteKind.WORMHOLE),
            gatewayY(world, site, groundY, settings, minY, maxY));

        demolishStrayShells(world, site, anchor);

        Location landing = new Location(world, centreX + 0.5, groundY + 1, centreZ + 0.5);
        placeGatewayShell(world, site, anchor.y(), landing);
        return landing;
    }

    /**
     * Takes down every shell in a wormhole's column that is not the wormhole.
     *
     * <p>This is the repair half of the anchor: a world that already grew a stack of gateways
     * collapses back to one the next time anybody travels through it, rather than keeping the mess
     * forever because the bedrock is unbreakable. Only bedrock and this site's own gateway blocks are
     * removed, and only from inside the strays' own shells, so nothing that was there first is
     * touched — in the End, bedrock within a few blocks of a gateway is a gateway's.
     *
     * <p>The surviving gateway block is skipped, because a stray sitting a block or two away has a
     * shell that overlaps it. Its bedrock is put back immediately afterwards by the rebuild.
     */
    private void demolishStrayShells(World world, Coord site, SiteAnchor anchor) {
        if (anchor.strays().isEmpty()) return;

        plugin.getLogger().info("[Travel] Wormhole " + site.key() + " had " + anchor.strays().size()
            + " duplicate gateway(s) at y " + anchor.strays() + "; removing them and keeping the one at y "
            + anchor.y() + ".");

        for (int strayY : anchor.strays()) {
            for (int dx = -SHELL_RADIUS; dx <= SHELL_RADIUS; dx++) {
                for (int dz = -SHELL_RADIUS; dz <= SHELL_RADIUS; dz++) {
                    for (int dy = -SHELL_HEIGHT; dy <= SHELL_HEIGHT; dy++) {
                        int x = site.x() + dx;
                        int y = strayY + dy;
                        int z = site.z() + dz;
                        if (x == site.x() && z == site.z() && y == anchor.y()) continue;
                        clearOurStructure(world, x, y, z, site);
                    }
                }
            }
        }
    }

    /**
     * How high the gateway floats. {@code wormholes.gatewayHeight} above the ground, unless something
     * the builder will not overwrite reaches that far, in which case the whole shell rises clear of
     * it — an end city keeps its towers and gets a gateway overhead rather than through it.
     *
     * <p>Asked once, on the day the wormhole is built, and never again: after that the gateway
     * standing there is the answer. See {@link SiteAnchor}.
     */
    private int gatewayY(World world, Coord site, int groundY, DimensionalTravelConfig settings,
                         int minY, int maxY) {
        int y = groundY + settings.getWormholeGatewayHeight();

        int protectedTop = highestProtectedY(world, site.x(), site.z(), SHELL_RADIUS, minY, maxY);
        if (protectedTop > Integer.MIN_VALUE && protectedTop >= y - SHELL_HEIGHT) {
            y = protectedTop + SHELL_HEIGHT + 1;
        }

        return Math.max(minY + SHELL_HEIGHT, Math.min(y, maxY));
    }

    /**
     * Places the vanilla gateway structure: a bedrock cross a block above and below the opening,
     * capped top and bottom, with the gateway block itself in the middle and its four sides open.
     *
     * <p>This is block for block what {@code EndGatewayFeature} builds, because a wormhole that
     * announced itself as something other than an End gateway would be telling the player something
     * untrue — every one of these behaves exactly like the ones the dragon leaves behind.
     */
    private void placeGatewayShell(World world, Coord site, int centreY, Location exit) {
        int centreX = site.x();
        int centreZ = site.z();

        for (int dx = -SHELL_RADIUS; dx <= SHELL_RADIUS; dx++) {
            for (int dz = -SHELL_RADIUS; dz <= SHELL_RADIUS; dz++) {
                for (int dy = -SHELL_HEIGHT; dy <= SHELL_HEIGHT; dy++) {
                    int x = centreX + dx;
                    int y = centreY + dy;
                    int z = centreZ + dz;
                    boolean onAxis = dx == 0 || dz == 0;
                    boolean column = dx == 0 && dz == 0;

                    if (dy == 0 && column) {
                        placeGatewayBlock(world, x, y, z, site, exit, SiteKind.WORMHOLE);
                    } else if (dy == 0 || (Math.abs(dy) == SHELL_HEIGHT && !column) || !onAxis) {
                        // The opening's four sides, and the corners of the cross. Vanilla clears
                        // these outright; we clear what we would have been allowed to place, and our
                        // own bedrock and gateways along with it. That last part is what unseals a
                        // wormhole an overlapping shell had bricked up: without it the opening stays
                        // bedrock forever, because bedrock is not a block this builder replaces.
                        clearShellBlock(world, x, y, z, site);
                    } else {
                        force(world, x, y, z, Material.BEDROCK);
                    }
                }
            }
        }
    }

    /**
     * Grows an End island, following vanilla's {@code EndIslandFeature}: a disc four to six blocks
     * across at the top, narrowing by half a block or more on each layer down, until it runs out.
     *
     * <p>The randomness is seeded on the island's own coordinates rather than left to chance, because
     * every build here runs again on the next visit. A fresh seed would redraw a different island
     * each time and pile them into a tower; this redraws the same one, and finds it already there.
     */
    private void growEndIsland(World world, Coord site, int topY) {
        Random random = new Random(site.x() * 341873128712L + site.z() * 132897987541L);
        float radius = random.nextInt(3) + 4;

        for (int dy = 0; radius > 0.5f; dy--) {
            int span = (int) Math.ceil(radius);
            long reach = (long) ((radius + 1.0f) * (radius + 1.0f));
            for (int dx = -span; dx <= span; dx++) {
                for (int dz = -span; dz <= span; dz++) {
                    if ((long) dx * dx + (long) dz * dz <= reach) {
                        setIfReplaceable(world, site.x() + dx, topY + dy, site.z() + dz,
                            Material.END_STONE);
                    }
                }
            }
            radius -= random.nextInt(2) + 0.5f;
        }
    }

    // -------------------------------------------------------------------------
    // Blocks
    // -------------------------------------------------------------------------

    private void placeGatewayBlock(World world, int x, int y, int z, Coord site, Location exit,
                                   SiteKind kind) {
        Block block = world.getBlockAt(x, y, z);
        if (kindOf(block) == kind && hasSiteOrigin(block)) {
            // Already ours, already the right kind and already labelled — leave it alone so
            // revisiting a site does not restart the beam animation.
            return;
        }

        if (block.getType() != Material.END_GATEWAY) {
            block.setType(Material.END_GATEWAY, false);
        }

        BlockState state = block.getState();
        if (state instanceof EndGateway gateway) {
            // An exact exit onto our own ground stops the server searching for (or generating) an
            // exit island. The authoritative teleport is done by the plugin; if that ever fails to
            // fire, the worst vanilla outcome is a harmless hop to the foot of this gateway.
            gateway.setExitLocation(exit);
            gateway.setExactTeleport(true);
            tag(gateway, site, kind);
            gateway.update(true, false);
        } else {
            plugin.getLogger().warning("Placed a " + kind.tag() + " gateway at " + x + "," + y + "," + z
                + " but could not read its block state; the site may need to be rebuilt.");
        }
    }

    private void tag(TileState tile, Coord site, SiteKind kind) {
        var container = tile.getPersistentDataContainer();
        container.set(gatewayKey, PersistentDataType.BYTE, (byte) 1);
        container.set(kindKey, PersistentDataType.STRING, kind.tag());
        container.set(siteXKey, PersistentDataType.INTEGER, site.x());
        container.set(siteZKey, PersistentDataType.INTEGER, site.z());
    }

    /** True if a gateway already records which site it belongs to, rather than needing an upgrade. */
    private boolean hasSiteOrigin(Block block) {
        return block.getState() instanceof TileState tile
            && tile.getPersistentDataContainer().has(siteXKey, PersistentDataType.INTEGER);
    }

    /**
     * The heights at which a site's own gateways stand in one column, lowest first.
     *
     * <p>This is the record a rebuild reads its height from, and it is the world rather than a file
     * because the tag rides along in the chunk. Ordinarily it holds exactly one entry; more than one
     * means a site has been built twice, which {@link SiteAnchor} resolves.
     *
     * @param x    the column to look down — a wormhole's centre, or a home platform's doorway
     * @param kind which kind of gateway counts here; the other kind in the same column is not this
     *             site, and is left alone
     */
    private List<Integer> ourGatewayColumn(World world, int x, int z, Coord site, SiteKind kind) {
        List<Integer> found = new ArrayList<>(2);

        for (int y = world.getMinHeight(); y < world.getMaxHeight(); y++) {
            Block block = world.getBlockAt(x, y, z);
            if (block.getType() != Material.END_GATEWAY) continue;
            if (!(block.getState() instanceof TileState tile)) continue;

            SiteKind tagged = kindOf(tile);
            if (tagged == null) {
                // Nobody has adopted this one yet. A gateway standing in the exact column a wormhole
                // belongs to is that wormhole — most likely one of the End's own, reached before the
                // adoption pass got to its chunk — and building a second shell around it is the
                // duplicate this all exists to prevent. Claiming it costs nothing: the rebuild tags
                // it, and it was going to be adopted as a wormhole of this cell regardless. A way
                // home is never claimed this way, because only this plugin ever builds one.
                if (kind == SiteKind.WORMHOLE) found.add(y);
                continue;
            }

            if (tagged == kind && belongsTo(tile, site)) found.add(y);
        }

        return found;
    }

    /** True if this block is one of our gateways and it belongs to the given site. */
    private boolean isOursFor(Block block, Coord site) {
        if (block.getType() != Material.END_GATEWAY) return false;
        if (!(block.getState() instanceof TileState tile)) return false;
        return kindOf(tile) != null && belongsTo(tile, site);
    }

    private boolean belongsTo(TileState tile, Coord site) {
        var container = tile.getPersistentDataContainer();
        Integer x = container.get(siteXKey, PersistentDataType.INTEGER);
        Integer z = container.get(siteZKey, PersistentDataType.INTEGER);

        // A gateway of ours from before centres were recorded. Standing in a column this site owns,
        // it is this site's — which is the same assumption the in-place upgrade already makes.
        if (x == null || z == null) return true;

        return x == site.x() && z == site.z();
    }

    private int minBuildY(World world) {
        return Math.max(world.getMinHeight() + 1, 8);
    }

    private int highestSolidY(World world, int x, int z, int minY, int maxY) {
        for (int y = maxY; y >= minY; y--) {
            Material type = world.getBlockAt(x, y, z).getType();
            // Our own frame is not terrain: counting it would let a rebuilt site climb off its floor.
            if (STRUCTURAL.contains(type)) continue;
            if (type.isSolid()) return y;
        }
        return Integer.MIN_VALUE;
    }

    private int highestProtectedY(World world, int centreX, int centreZ, int radius, int minY, int maxY) {
        int highest = Integer.MIN_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int y = maxY; y > highest && y >= minY; y--) {
                    Material type = world.getBlockAt(centreX + dx, y, centreZ + dz).getType();
                    if (type.isAir() || isOurMasonry(type) || STRUCTURAL.contains(type)) continue;
                    highest = y;
                    break;
                }
            }
        }
        return highest;
    }

    private boolean isNaturallyLandable(World world, int x, int feetY, int z) {
        Block ground = world.getBlockAt(x, feetY - 1, z);
        Block feet = world.getBlockAt(x, feetY, z);
        Block head = world.getBlockAt(x, feetY + 1, z);
        return ground.getType().isSolid() && feet.isPassable() && head.isPassable();
    }

    private void setIfReplaceable(World world, int x, int y, int z, Material material) {
        Block block = world.getBlockAt(x, y, z);
        if (block.getType() == material) return;
        if (!REPLACEABLE.contains(block.getType())) return;
        block.setType(material, false);
    }

    /**
     * Clears a block inside a gateway shell — the one place this builder takes its own bedrock back
     * out again.
     *
     * <p>Everywhere else bedrock is only ever placed, because a door that could be mined out is not a
     * door. Inside a shell it has to work both ways: every position in that 3×5×3 box belongs to the
     * gateway, so bedrock found there is either part of the shell about to be rebuilt or the wreckage
     * of one that should never have been built, and leaving it is how a wormhole ends up sealed.
     * Anything that is not ours is still left exactly where it is.
     */
    private void clearShellBlock(World world, int x, int y, int z, Coord site) {
        Block block = world.getBlockAt(x, y, z);
        if (block.isEmpty()) return;

        Material type = block.getType();
        if (!REPLACEABLE.contains(type) && type != Material.BEDROCK && !isOursFor(block, site)) return;
        block.setType(Material.AIR, false);
    }

    /**
     * Takes out a piece of a shell that should not be standing: our bedrock and our gateways, and
     * nothing else at all.
     *
     * <p>Narrower than {@link #clearShellBlock} on purpose. A shell being demolished may overlap the
     * ground — end stone is fair game to a shell being <em>built</em>, since vanilla hollows its
     * opening out of whatever is there, but punching a hole through an island while tidying up a
     * duplicate would be replacing one piece of damage with another.
     */
    private void clearOurStructure(World world, int x, int y, int z, Coord site) {
        Block block = world.getBlockAt(x, y, z);
        if (block.getType() != Material.BEDROCK && !isOursFor(block, site)) return;
        block.setType(Material.AIR, false);
    }

    /**
     * As {@link #setIfReplaceable}, but a home site's own masonry counts as fair game.
     *
     * <p>That is what makes the terrace repair itself: a rebuild lays every block down again, and a
     * corner replaced with the wrong material — or with last version's — is simply corrected.
     */
    private void setIfOurs(World world, int x, int y, int z, Material material) {
        Block block = world.getBlockAt(x, y, z);
        if (block.getType() == material) return;
        if (!isOurMasonry(block.getType())) return;
        block.setType(material, false);
    }

    private void clearIfOurs(World world, int x, int y, int z) {
        Block block = world.getBlockAt(x, y, z);
        if (block.isEmpty()) return;
        if (!isOurMasonry(block.getType())) return;
        block.setType(Material.AIR, false);
    }

    private boolean isOurMasonry(Material type) {
        return REPLACEABLE.contains(type) || MASONRY.contains(type);
    }

    /**
     * Places a bottom-half straight stair. Physics is suppressed so neighbouring stairs do not talk
     * each other into inner and outer corners — every stair here is meant to be the plain shape.
     */
    private void placeStairs(World world, int x, int y, int z, BlockFace facing) {
        Block block = world.getBlockAt(x, y, z);
        if (!isOurMasonry(block.getType())) return;

        Stairs stairs = (Stairs) Material.END_STONE_BRICK_STAIRS.createBlockData();
        stairs.setFacing(facing);
        stairs.setHalf(Bisected.Half.BOTTOM);
        stairs.setShape(Stairs.Shape.STRAIGHT);

        BlockData current = block.getBlockData();
        if (current.equals(stairs)) return;
        block.setBlockData(stairs, false);
    }

    /** Places a structural block regardless of what is there, used only for bedrock frames. */
    private void force(World world, int x, int y, int z, Material material) {
        Block block = world.getBlockAt(x, y, z);
        if (block.getType() == material) return;
        block.setType(material, false);
    }
}
