package dev.unrau.samsara.path;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything Minecraft itself knows about a player, lifted off them or put back.
 *
 * <p>This is what makes a path an existence rather than a bookmark. Switching has to feel like
 * logging out of one player and into another, and the only way it can is if the thing that moves is
 * the whole player — where they stand, what they are carrying, what is in their ender chest, what
 * they have learned to survive, and how close to dying they were when they left.
 *
 * <p>Three things are deliberately <em>not</em> here, and each of them is the same judgement:
 * advancements, statistics and unlocked recipes belong to the person holding the account, not to
 * the existence they are currently living. They already survive death, which is the strongest
 * possible statement Samsara makes about what a life owns, so a path does not get its own copy of
 * them either. Social records are apart for exactly this reason too — see {@code SocialStore}.
 *
 * <p>Nothing here is chosen for convenience. Every field is something a player would notice missing
 * on the other side of a switch, and a field that only <em>mattered</em> would be a field that
 * quietly resets an existence every time its owner looked away.
 */
public final class IncarnationState {

    /** Bukkit's own layout: 36 storage slots, 4 of armour, then the off hand. */
    public static final int INVENTORY_SLOTS = 41;

    /** The vanilla ender chest, which has never been any other size. */
    public static final int ENDER_CHEST_SLOTS = 27;

    private final String world;
    private final double x;
    private final double y;
    private final double z;
    private final float yaw;
    private final float pitch;

    private final ItemStack[] inventory;
    private final int heldSlot;
    private final ItemStack[] enderChest;

    private final int level;
    private final float exp;
    private final int totalExperience;

    private final double health;
    private final int foodLevel;
    private final float saturation;
    private final float exhaustion;

    private final int remainingAir;
    private final int fireTicks;
    private final float fallDistance;

    /** Null means "leave it alone", which is what a brand new path asks for. */
    private final GameMode gameMode;

    private final List<PotionEffect> effects;

    public IncarnationState(String world, double x, double y, double z, float yaw, float pitch,
                            ItemStack[] inventory, int heldSlot, ItemStack[] enderChest,
                            int level, float exp, int totalExperience,
                            double health, int foodLevel, float saturation, float exhaustion,
                            int remainingAir, int fireTicks, float fallDistance,
                            GameMode gameMode, List<PotionEffect> effects) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.inventory = inventory;
        this.heldSlot = heldSlot;
        this.enderChest = enderChest;
        this.level = level;
        this.exp = exp;
        this.totalExperience = totalExperience;
        this.health = health;
        this.foodLevel = foodLevel;
        this.saturation = saturation;
        this.exhaustion = exhaustion;
        this.remainingAir = remainingAir;
        this.fireTicks = fireTicks;
        this.fallDistance = fallDistance;
        this.gameMode = gameMode;
        this.effects = List.copyOf(effects);
    }

    // -------------------------------------------------------------------------
    // Taking it off, and putting it back
    // -------------------------------------------------------------------------

    /** Everything about this player, as they stand. */
    public static IncarnationState capture(Player player) {
        return captureAt(player, player.getLocation());
    }

    /**
     * Everything about this player, but standing somewhere else.
     *
     * <p>Exists for one reason, and it is the reason a switch cannot duplicate an item. Moving a
     * player is asynchronous — the ground they are going to may never have been generated — and in
     * the second that takes they can still drop something, be hit, or die. So the existence being
     * put away is read <em>after</em> the move has finished, when nothing more can happen to it,
     * and only its position is taken from before: the place it was actually living.
     *
     * @param where the position this existence occupies, which is not where the player is now
     */
    public static IncarnationState captureAt(Player player, Location where) {
        Location at = where;
        PlayerInventory inventory = player.getInventory();

        return new IncarnationState(
            at.getWorld().getName(), at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch(),
            copyOf(inventory.getContents(), INVENTORY_SLOTS),
            inventory.getHeldItemSlot(),
            copyOf(player.getEnderChest().getContents(), ENDER_CHEST_SLOTS),
            player.getLevel(), player.getExp(), player.getTotalExperience(),
            player.getHealth(), player.getFoodLevel(), player.getSaturation(), player.getExhaustion(),
            player.getRemainingAir(), player.getFireTicks(), player.getFallDistance(),
            player.getGameMode(),
            new ArrayList<>(player.getActivePotionEffects())
        );
    }

    /**
     * A path that has not been lived yet: nothing carried, nothing learned, nothing hurt.
     *
     * <p>The game mode is left null on purpose. A new path is a new existence, not a new set of
     * server permissions, and an operator who was building in creative when they began one should
     * not have to work out why the world suddenly hurts.
     */
    public static IncarnationState freshAt(Location where) {
        return new IncarnationState(
            where.getWorld().getName(), where.getX(), where.getY(), where.getZ(),
            where.getYaw(), where.getPitch(),
            new ItemStack[INVENTORY_SLOTS], 0, new ItemStack[ENDER_CHEST_SLOTS],
            0, 0f, 0,
            20.0, 20, 5f, 0f,
            300, 0, 0f,
            null, List.of()
        );
    }

    /**
     * Puts this state onto a player, everything except where they are standing.
     *
     * <p>The position is the caller's business, because moving a player across a world that may not
     * be generated yet is asynchronous and everything here is not. A caller that applies this
     * without also honouring {@link #location()} has put one existence's belongings into another
     * existence's place.
     */
    public void applyTo(Player player) {
        for (PotionEffect existing : player.getActivePotionEffects()) {
            player.removePotionEffect(existing.getType());
        }
        for (PotionEffect effect : effects) {
            player.addPotionEffect(effect);
        }

        PlayerInventory inventory = player.getInventory();
        inventory.setContents(fit(this.inventory, inventory.getSize()));
        inventory.setHeldItemSlot(Math.max(0, Math.min(heldSlot, 8)));
        player.getEnderChest().setContents(fit(enderChest, player.getEnderChest().getSize()));

        // Set the total last: setLevel and setExp each recompute it, so writing it first would be
        // undone and a player would be handed back a bar that disagrees with their own count.
        player.setLevel(Math.max(0, level));
        player.setExp(clamp(exp));
        player.setTotalExperience(Math.max(0, totalExperience));

        player.setHealth(Math.max(0.5, Math.min(health, maxHealthOf(player))));
        player.setFoodLevel(Math.max(0, Math.min(foodLevel, 20)));
        player.setSaturation(saturation);
        player.setExhaustion(exhaustion);

        player.setRemainingAir(Math.min(remainingAir, player.getMaximumAir()));
        player.setFireTicks(Math.max(0, fireTicks));
        player.setFallDistance(Math.max(0f, fallDistance));

        if (gameMode != null) {
            player.setGameMode(gameMode);
        }
    }

    /** Everything this state is carrying, in one list, for a path being abandoned. */
    public List<ItemStack> belongings() {
        List<ItemStack> all = new ArrayList<>();
        collect(inventory, all);
        collect(enderChest, all);
        return all;
    }

    private static void collect(ItemStack[] from, List<ItemStack> into) {
        for (ItemStack item : from) {
            if (item != null && item.getType().isItem() && item.getAmount() > 0) {
                into.add(item);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Reading it
    // -------------------------------------------------------------------------

    public String worldName()        { return world; }
    public double x()                { return x; }
    public double y()                { return y; }
    public double z()                { return z; }
    public float yaw()               { return yaw; }
    public float pitch()             { return pitch; }
    public ItemStack[] inventory()   { return inventory.clone(); }
    public int heldSlot()            { return heldSlot; }
    public ItemStack[] enderChest()  { return enderChest.clone(); }
    public int level()               { return level; }
    public float exp()               { return exp; }
    public int totalExperience()     { return totalExperience; }
    public double health()           { return health; }
    public int foodLevel()           { return foodLevel; }
    public float saturation()        { return saturation; }
    public float exhaustion()        { return exhaustion; }
    public int remainingAir()        { return remainingAir; }
    public int fireTicks()           { return fireTicks; }
    public float fallDistance()      { return fallDistance; }
    public GameMode gameMode()       { return gameMode; }
    public List<PotionEffect> effects() { return effects; }

    /**
     * Where this existence is standing, or null if its world is no longer loaded.
     *
     * <p>A path whose world has been removed cannot be walked back into, and saying so with a null
     * is the whole of the handling: the alternative is putting somebody down at a coordinate in a
     * world that has nothing at it.
     */
    public Location location() {
        org.bukkit.World loaded = org.bukkit.Bukkit.getWorld(world);
        return loaded == null ? null : new Location(loaded, x, y, z, yaw, pitch);
    }

    // -------------------------------------------------------------------------
    // Plumbing
    // -------------------------------------------------------------------------

    /** A defensive copy of exactly {@code size} slots, however many the server handed over. */
    private static ItemStack[] copyOf(ItemStack[] source, int size) {
        ItemStack[] slots = new ItemStack[size];
        for (int i = 0; i < size && i < source.length; i++) {
            slots[i] = source[i] == null ? null : source[i].clone();
        }
        return slots;
    }

    /**
     * The stored slots, resized to what this server's inventory actually holds.
     *
     * <p>Bukkit refuses contents longer than the inventory, and a future version that adds a slot
     * would otherwise hand every returning path a short array. Neither is worth an exception in the
     * middle of a switch.
     */
    private static ItemStack[] fit(ItemStack[] stored, int size) {
        if (stored.length == size) return stored.clone();
        return copyOf(stored, size);
    }

    private static float clamp(float progress) {
        if (Float.isNaN(progress) || progress < 0f) return 0f;
        return Math.min(progress, 0.9999f);
    }

    private static double maxHealthOf(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.MAX_HEALTH);
        return attribute == null ? 20.0 : attribute.getValue();
    }
}
