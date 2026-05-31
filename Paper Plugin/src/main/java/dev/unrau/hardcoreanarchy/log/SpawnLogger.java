package dev.unrau.hardcoreanarchy.log;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import java.util.logging.Level;

public class SpawnLogger {

    public enum Reason { FIRST_JOIN, DEATH, EXILE_RESPAWN }

    private final JavaPlugin plugin;
    private final File logFile;
    private final boolean enabled;

    public SpawnLogger(JavaPlugin plugin, boolean enabled) {
        this.plugin = plugin;
        this.enabled = enabled;
        this.logFile = new File(plugin.getDataFolder(), "exile-log.txt");
    }

    public void log(Reason reason, UUID uuid, String playerName, String world, double x, double y, double z) {
        if (!enabled) return;

        String line = String.format("%s | %-13s | uuid=%s | player=%s | world=%s | x=%d | y=%d | z=%d",
            Instant.now().toString(),
            reason.name(),
            uuid.toString(),
            playerName,
            world,
            (long) x,
            (long) y,
            (long) z
        );

        // Append synchronously — this is called from the main server thread
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(logFile, true))) {
            writer.write(line);
            writer.newLine();
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to write to exile-log.txt", e);
        }
    }
}
