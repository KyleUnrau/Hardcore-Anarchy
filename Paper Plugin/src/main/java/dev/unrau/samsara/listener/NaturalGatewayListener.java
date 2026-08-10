package dev.unrau.samsara.listener;

import dev.unrau.samsara.config.DimensionalTravelConfig;
import dev.unrau.samsara.service.DimensionalTravelService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.EndGateway;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Brings the End's own gateways into the network, the moment their chunk loads.
 *
 * <p>The ring the dragon leaves behind, and anything an older world already had standing, are
 * ordinary vanilla gateways until somebody claims them. Left alone they do two things this server
 * cannot have:
 *
 * <ul>
 *   <li>They lead to the <b>outer islands</b>, and from there back to the central island. That is
 *       the one hub Samsara is built to not have.</li>
 *   <li>They <b>make one</b>. Vanilla resolves a gateway's destination the first time anything
 *       enters it, generating an outer island and a return gateway if the region is empty — and it
 *       does that before any plugin is consulted, so cancelling the teleport afterwards is already
 *       too late. The island and its gateway are left behind whatever the plugin then decides.</li>
 * </ul>
 *
 * <p>Adopting on chunk load is what gets in front of that. It costs a lookup of the chunk's gateway
 * blocks — of which the End has almost none — and it happens long before a player is close enough to
 * step into one.
 *
 * <p>The work itself is idempotent and belongs to
 * {@link DimensionalTravelService#adoptNaturalGateway}; a gateway already in the network is left
 * exactly as it is, so a chunk that loads a thousand times is written to once.
 *
 * <p>Looking is done inline and writing is not. Reading a chunk's block entities as it loads is
 * cheap and harmless; editing one is the sort of thing that is better done a tick later, when the
 * chunk is unambiguously somebody else's problem. Since almost no End chunk contains a gateway at
 * all, the deferral costs a scheduled task only on the rare chunk that does.
 */
public class NaturalGatewayListener implements Listener {

    private final JavaPlugin plugin;
    private final DimensionalTravelService travelService;

    public NaturalGatewayListener(JavaPlugin plugin, DimensionalTravelService travelService) {
        this.plugin = plugin;
        this.travelService = travelService;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        DimensionalTravelConfig settings = travelService.settings();
        if (!settings.isEnabled() || !settings.isWormholesEnabled()) return;

        World world = event.getWorld();
        if (world.getEnvironment() != World.Environment.THE_END) return;
        if (!world.getName().equals(settings.getEndWorldName())) return;

        // The predicate form reads the chunk's block entities without copying any of them, so a
        // chunk with no gateway in it — which is very nearly all of them — costs a map scan.
        if (event.getChunk().getTileEntities(block -> block.getType() == Material.END_GATEWAY, false)
                .isEmpty()) {
            return;
        }

        int chunkX = event.getChunk().getX();
        int chunkZ = event.getChunk().getZ();
        Bukkit.getScheduler().runTask(plugin, () -> adopt(world, chunkX, chunkZ));
    }

    private void adopt(World world, int chunkX, int chunkZ) {
        // A tick has passed; the chunk may have gone again, and asking for it by name would drag it
        // straight back in. There will be another chunk load if it matters.
        if (!world.isChunkLoaded(chunkX, chunkZ)) return;

        for (BlockState state : world.getChunkAt(chunkX, chunkZ)
                .getTileEntities(block -> block.getType() == Material.END_GATEWAY, false)) {
            if (state instanceof EndGateway gateway) {
                travelService.adoptNaturalGateway(gateway);
            }
        }
    }
}
