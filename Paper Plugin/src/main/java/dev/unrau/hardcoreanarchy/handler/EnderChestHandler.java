package dev.unrau.hardcoreanarchy.handler;

import dev.unrau.hardcoreanarchy.config.PluginConfig;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Handles Ender chest de-vaulting on death.
 *
 * Drops contents into the world at the death location so valuables become
 * lootable. This prevents Ender chests from acting as a reincarnation vault
 * while also avoiding item deletion (which would reward cautious players who
 * intentionally store Curse-of-Vanishing items).
 */
public class EnderChestHandler {

    private final PluginConfig config;

    public EnderChestHandler(PluginConfig config) {
        this.config = config;
    }

    /**
     * Drops and clears the player's Ender chest inventory at the given location.
     * Must be called from the main server thread during PlayerDeathEvent.
     */
    public void dropAndClear(Player player, Location deathLocation) {
        if (!config.isClearEnderChestOnDeath()) return;

        Inventory enderChest = player.getEnderChest();
        ItemStack[] contents = enderChest.getContents();
        boolean hasItems = false;

        for (ItemStack item : contents) {
            if (item != null && item.getType().isItem()) {
                hasItems = true;
                break;
            }
        }

        if (!hasItems) return;

        if (config.isDropEnderChestContentsOnDeath()) {
            for (ItemStack item : contents) {
                if (item != null && item.getType().isItem()) {
                    deathLocation.getWorld().dropItemNaturally(deathLocation, item);
                }
            }
        }

        enderChest.clear();
    }
}
