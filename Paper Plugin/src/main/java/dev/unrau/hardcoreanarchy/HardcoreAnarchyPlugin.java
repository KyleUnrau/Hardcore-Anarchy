package dev.unrau.hardcoreanarchy;

import dev.unrau.hardcoreanarchy.command.HeaCommand;
import dev.unrau.hardcoreanarchy.config.PluginConfig;
import dev.unrau.hardcoreanarchy.data.PlayerDataStore;
import dev.unrau.hardcoreanarchy.handler.EnderChestHandler;
import dev.unrau.hardcoreanarchy.listener.*;
import dev.unrau.hardcoreanarchy.log.SpawnLogger;
import dev.unrau.hardcoreanarchy.service.ExileSpawnService;
import dev.unrau.hardcoreanarchy.service.SafeLocationFinder;
import org.bukkit.plugin.java.JavaPlugin;

public class HardcoreAnarchyPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        saveDefaultConfig();

        PluginConfig config = new PluginConfig(this);
        PlayerDataStore dataStore = new PlayerDataStore(this);
        SpawnLogger spawnLogger = new SpawnLogger(this, config.isLogSpawnLocations());
        SafeLocationFinder locationFinder = new SafeLocationFinder(config);
        ExileSpawnService spawnService = new ExileSpawnService(this, config, locationFinder, dataStore, spawnLogger);
        EnderChestHandler enderChestHandler = new EnderChestHandler(config);

        getServer().getPluginManager().registerEvents(new FirstJoinListener(this, config, dataStore, spawnService, spawnLogger), this);
        getServer().getPluginManager().registerEvents(new DeathListener(dataStore, enderChestHandler, spawnService, spawnLogger), this);
        getServer().getPluginManager().registerEvents(new RespawnListener(this, config, dataStore), this);
        getServer().getPluginManager().registerEvents(new BedListener(config), this);
        getServer().getPluginManager().registerEvents(new RespawnAnchorListener(config), this);

        String version = getDescription().getVersion();
        getCommand("hea").setExecutor(new HeaCommand(config, spawnService, version));

        getLogger().info("HardcoreAnarchy v" + version + " enabled. Death exiles you.");
    }

    @Override
    public void onDisable() {
        getLogger().info("HardcoreAnarchy disabled.");
    }
}
