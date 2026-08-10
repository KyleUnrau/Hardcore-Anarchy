package dev.unrau.samsara.config;

import org.bukkit.configuration.ConfigurationSection;

import java.util.logging.Logger;

/**
 * Settings for End travel.
 *
 * <p>Values are clamped to workable ranges on load, with a warning, so a typo in config.yml degrades
 * the feature rather than stranding players in the void.
 */
public class DimensionalTravelConfig {

    private final boolean enabled;
    private final String overworldName;
    private final String endWorldName;

    private final int arrivalSiteSpacing;
    private final int arrivalPlatformRadius;
    private final int arrivalPlatformY;
    private final boolean buildArrivalSites;
    private final int centralIslandProtectRadius;

    private final boolean gatewaysEnabled;
    private final int gatewaySpacing;
    private final int gatewaySeparation;
    private final int gatewayMaterialiseRadius;
    private final int gatewayScanIntervalTicks;

    private final boolean wormholesEnabled;
    private final int wormholeCellSize;
    private final int wormholeGatewayHeight;
    private final long wormholeSeed;

    private final int returnSearchRadius;
    private final int portalCooldownTicks;
    private final boolean immediateTransition;
    private final boolean debug;

    private DimensionalTravelConfig(Builder builder) {
        this.enabled = builder.enabled;
        this.overworldName = builder.overworldName;
        this.endWorldName = builder.endWorldName;
        this.arrivalSiteSpacing = builder.arrivalSiteSpacing;
        this.arrivalPlatformRadius = builder.arrivalPlatformRadius;
        this.arrivalPlatformY = builder.arrivalPlatformY;
        this.buildArrivalSites = builder.buildArrivalSites;
        this.centralIslandProtectRadius = builder.centralIslandProtectRadius;
        this.gatewaysEnabled = builder.gatewaysEnabled;
        this.gatewaySpacing = builder.gatewaySpacing;
        this.gatewaySeparation = builder.gatewaySeparation;
        this.gatewayMaterialiseRadius = builder.gatewayMaterialiseRadius;
        this.gatewayScanIntervalTicks = builder.gatewayScanIntervalTicks;
        this.wormholesEnabled = builder.wormholesEnabled;
        this.wormholeCellSize = builder.wormholeCellSize;
        this.wormholeGatewayHeight = builder.wormholeGatewayHeight;
        this.wormholeSeed = builder.wormholeSeed;
        this.returnSearchRadius = builder.returnSearchRadius;
        this.portalCooldownTicks = builder.portalCooldownTicks;
        this.immediateTransition = builder.immediateTransition;
        this.debug = builder.debug;
    }

    /** Mutable carrier for the defaults, so reading the section stays one assignment per key. */
    private static final class Builder {
        boolean enabled = true;
        String overworldName;
        String endWorldName = "world_the_end";
        int arrivalSiteSpacing = 16;
        int arrivalPlatformRadius = 3;
        int arrivalPlatformY = 64;
        boolean buildArrivalSites = true;
        int centralIslandProtectRadius = 1024;
        boolean gatewaysEnabled = true;
        // One gateway per 512-block cell, scattered inside it and never closer than 128 blocks to
        // its neighbour. Sized by how often a gateway drifts into view along a straight flight —
        // around a thousand blocks of deliberate exploring — rather than by gateways per square
        // kilometre, which is not a thing a player can perceive.
        //
        // The separation is deliberately well under half the spacing. It buys the margin that keeps
        // neighbours apart, but that same margin is a band of coordinates no gateway can occupy, and
        // a player flying straight down the middle of one sees nothing until their sight range
        // reaches half the separation. At 128 that is 64 blocks, four chunks, which every player
        // clears; at 320 it would be 160 and the lattice's permanently-blind courses would be back
        // in a subtler form.
        int gatewaySpacing = 512;
        int gatewaySeparation = 128;
        int gatewayMaterialiseRadius = 192;
        int gatewayScanIntervalTicks = 100;
        boolean wormholesEnabled = true;
        int wormholeCellSize = 16;
        int wormholeGatewayHeight = 10;
        long wormholeSeed = 0L;
        int returnSearchRadius = 16;
        int portalCooldownTicks = 100;
        boolean immediateTransition = true;
        boolean debug = false;
    }

    /**
     * Reads the {@code dimensionalTravel} section. A missing section yields defaults with the
     * feature on, so an existing server that upgrades the jar gets the End rules without editing
     * config.yml — and can turn them off with a single key.
     *
     * @param section the {@code dimensionalTravel} section, or the legacy {@code endTravel} section
     *                that preceded it, or null for defaults
     */
    public static DimensionalTravelConfig from(ConfigurationSection section, String defaultOverworldName,
                                               Logger logger) {
        Builder builder = new Builder();
        builder.overworldName = defaultOverworldName;
        if (section == null) {
            return new DimensionalTravelConfig(builder);
        }

        builder.enabled = section.getBoolean("enabled", builder.enabled);
        builder.overworldName = section.getString("overworldName", defaultOverworldName);
        builder.endWorldName = section.getString("endWorldName", builder.endWorldName);
        builder.buildArrivalSites = section.getBoolean("buildArrivalSites", builder.buildArrivalSites);
        builder.immediateTransition = section.getBoolean("immediateTransition", builder.immediateTransition);
        builder.debug = section.getBoolean("debug", builder.debug);

        warnObsolete(section, logger, "overworldToEndScale",
            "the End is now a reflection of the Overworld, (x, z) -> (-z, -x), not a scaling of it");
        warnObsolete(section, logger, "netherFromEnd",
            "End-to-Nether travel has been removed; Overworld <-> Nether is vanilla in both directions");
        warnObsolete(section, logger, "netherPortals",
            "the plugin no longer routes any Nether portal");
        warnObsolete(section, logger, "netherWorldName",
            "the plugin no longer routes any Nether portal");

        builder.arrivalSiteSpacing = bounded(section, "arrivalSiteSpacing", builder.arrivalSiteSpacing,
            1, 512, logger);
        builder.arrivalPlatformRadius = bounded(section, "arrivalPlatformRadius", builder.arrivalPlatformRadius,
            2, 8, logger);
        builder.arrivalPlatformY = bounded(section, "arrivalPlatformY", builder.arrivalPlatformY,
            8, 200, logger);
        builder.returnSearchRadius = bounded(section, "returnSearchRadius", builder.returnSearchRadius,
            1, 64, logger);
        builder.portalCooldownTicks = bounded(section, "portalCooldownTicks", builder.portalCooldownTicks,
            0, 12_000, logger);

        // Renamed from centralIslandExclusionRadius, which pushed arrival sites off the island and so
        // broke the reflection. It now only stops the plugin building there; routing is never bent.
        int protectRadius = section.getInt("centralIslandProtectRadius",
            section.getInt("centralIslandExclusionRadius", builder.centralIslandProtectRadius));
        builder.centralIslandProtectRadius = clamp("centralIslandProtectRadius", protectRadius,
            0, 100_000, builder.centralIslandProtectRadius, logger);

        ConfigurationSection gateways = section.getConfigurationSection("gateways");
        if (gateways != null) {
            builder.gatewaysEnabled = gateways.getBoolean("enabled", builder.gatewaysEnabled);
            builder.gatewaySpacing = bounded(gateways, "gateways.spacing", "spacing",
                builder.gatewaySpacing, 128, 100_000, logger);
            builder.gatewaySeparation = bounded(gateways, "gateways.separation", "separation",
                builder.gatewaySeparation, 2, 100_000, logger);
            builder.gatewayMaterialiseRadius = bounded(gateways, "gateways.materialiseRadius",
                "materialiseRadius", builder.gatewayMaterialiseRadius, 16, 1024, logger);
            builder.gatewayScanIntervalTicks = bounded(gateways, "gateways.scanIntervalTicks",
                "scanIntervalTicks", builder.gatewayScanIntervalTicks, 20, 12_000, logger);
        }

        ConfigurationSection wormholes = section.getConfigurationSection("wormholes");
        if (wormholes != null) {
            builder.wormholesEnabled = wormholes.getBoolean("enabled", builder.wormholesEnabled);
            builder.wormholeCellSize = bounded(wormholes, "wormholes.cellSize", "cellSize",
                builder.wormholeCellSize, 1, 4096, logger);
            // The floor is five, not zero: a traveller lands on the ground beneath the gateway, and
            // the shell reaches two blocks below its opening. Anything lower would drop somebody
            // into the bedrock they are meant to be standing under.
            builder.wormholeGatewayHeight = bounded(wormholes, "wormholes.gatewayHeight",
                "gatewayHeight", builder.wormholeGatewayHeight, 5, 64, logger);
            builder.wormholeSeed = wormholes.getLong("seed", builder.wormholeSeed);

            warnObsolete(wormholes, logger, "wormholes.reach", "reach",
                "the network now always spans the End's world border. A shorter reach folded every"
                    + " coordinate beyond it onto one shared cell, so gateways out there led"
                    + " somewhere real but never led back");
        }

        if (builder.gatewaySeparation >= builder.gatewaySpacing) {
            // The scatter needs room to move inside a cell: a separation of a whole cell would pin
            // every gateway to one spot and put the lattice — and its unreachable rows — back.
            int reduced = Math.max(2, builder.gatewaySpacing / 2);
            logger.warning("dimensionalTravel.gateways.separation (" + builder.gatewaySeparation
                + ") must be smaller than spacing (" + builder.gatewaySpacing + "); using " + reduced
                + ". Gateways would otherwise have nowhere to scatter to inside their cell.");
            builder.gatewaySeparation = reduced;
        } else if (builder.gatewaySeparation > builder.gatewaySpacing / 2) {
            // The margin that keeps neighbours apart is also a band no gateway can occupy, and a
            // course down the middle of one is blind until sight range reaches half the separation.
            logger.warning("dimensionalTravel.gateways.separation (" + builder.gatewaySeparation
                + ") is more than half of spacing (" + builder.gatewaySpacing + "); players flying a"
                + " straight cardinal course will not see a gateway until their sight range exceeds "
                + (builder.gatewaySeparation / 2) + " blocks. Lower it if that is not intended.");
        }

        if (builder.centralIslandProtectRadius >= builder.gatewaySpacing * 4L) {
            // Nodes inside the protected centre are skipped rather than moved, so a radius spanning
            // several cells is a genuinely empty region, not a ring pushed outwards.
            logger.warning("dimensionalTravel.centralIslandProtectRadius ("
                + builder.centralIslandProtectRadius + ") covers several gateway cells of "
                + builder.gatewaySpacing + " blocks; expect no distributed gateways within roughly "
                + builder.centralIslandProtectRadius + " blocks of End 0,0.");
        }

        if (builder.gatewaysEnabled && !builder.wormholesEnabled) {
            // The grid exists to put wormholes within reach. Without wormholes its nodes are inert
            // decoration, and a server owner who turned one off almost certainly meant both.
            logger.warning("dimensionalTravel.gateways is enabled but wormholes are not; the grid will"
                + " build gateways that do nothing. Turn gateways off too, or turn wormholes on.");
        }

        return new DimensionalTravelConfig(builder);
    }

    private static void warnObsolete(ConfigurationSection section, Logger logger, String key,
                                     String because) {
        warnObsolete(section, logger, key, key, because);
    }

    /** Reports a retired key from a nested section by its full path, so the warning is actionable. */
    private static void warnObsolete(ConfigurationSection section, Logger logger, String path,
                                     String key, String because) {
        if (!section.isSet(key)) return;
        logger.warning("dimensionalTravel." + path + " is no longer used: " + because
            + ". The key is ignored and can be deleted.");
    }

    /** Reads a key whose config path matches its name within the section. */
    private static int bounded(ConfigurationSection section, String key, int fallback,
                               int min, int max, Logger logger) {
        return bounded(section, key, key, fallback, min, max, logger);
    }

    /** Reads a key from a nested section, reporting the full path so the warning is actionable. */
    private static int bounded(ConfigurationSection section, String path, String key, int fallback,
                               int min, int max, Logger logger) {
        return clamp(path, section.getInt(key, fallback), min, max, fallback, logger);
    }

    private static int clamp(String path, int value, int min, int max, int fallback, Logger logger) {
        if (value < min || value > max) {
            logger.warning("dimensionalTravel." + path + " must be between " + min + " and " + max
                + " (got " + value + "); using " + fallback + ".");
            return fallback;
        }
        return value;
    }

    public boolean isEnabled()                 { return enabled; }
    public String getOverworldName()           { return overworldName; }
    public String getEndWorldName()            { return endWorldName; }
    public int getArrivalSiteSpacing()         { return arrivalSiteSpacing; }
    public int getArrivalPlatformRadius()      { return arrivalPlatformRadius; }
    public int getArrivalPlatformY()           { return arrivalPlatformY; }
    public boolean isBuildArrivalSites()       { return buildArrivalSites; }
    public int getCentralIslandProtectRadius() { return centralIslandProtectRadius; }
    public boolean isGatewaysEnabled()         { return gatewaysEnabled; }
    public int getGatewaySpacing()             { return gatewaySpacing; }
    public int getGatewaySeparation()          { return gatewaySeparation; }
    public int getGatewayMaterialiseRadius()   { return gatewayMaterialiseRadius; }
    public int getGatewayScanIntervalTicks()   { return gatewayScanIntervalTicks; }
    public boolean isWormholesEnabled()        { return wormholesEnabled; }
    public int getWormholeCellSize()           { return wormholeCellSize; }
    public int getWormholeGatewayHeight()      { return wormholeGatewayHeight; }
    public long getWormholeSeed()              { return wormholeSeed; }
    public int getReturnSearchRadius()         { return returnSearchRadius; }
    public int getPortalCooldownTicks()        { return portalCooldownTicks; }
    public boolean isImmediateTransition()     { return immediateTransition; }
    public boolean isDebug()                   { return debug; }
}
