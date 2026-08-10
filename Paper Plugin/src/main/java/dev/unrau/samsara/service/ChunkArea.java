package dev.unrau.samsara.service;

import org.bukkit.Chunk;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Loads the chunks around a destination without blocking the server.
 *
 * <p>Every destination in this plugin may be millions of blocks from anyone, in terrain that has
 * never been generated. Reading or building there on the main thread would stall the whole server
 * for as long as generation takes, so the rule throughout is: load asynchronously, then do the world
 * work in a scheduled main-thread task.
 */
final class ChunkArea {

    private ChunkArea() {
    }

    /** Completes once every chunk covering {@code blockRadius} around the point is loaded. */
    static CompletableFuture<Void> load(World world, int blockX, int blockZ, int blockRadius) {
        int minChunkX = (blockX - blockRadius) >> 4;
        int maxChunkX = (blockX + blockRadius) >> 4;
        int minChunkZ = (blockZ - blockRadius) >> 4;
        int maxChunkZ = (blockZ + blockRadius) >> 4;

        List<CompletableFuture<Chunk>> futures = new ArrayList<>();
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                futures.add(world.getChunkAtAsync(cx, cz));
            }
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }
}
