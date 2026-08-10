package dev.unrau.samsara.listener;

import dev.unrau.samsara.social.SocialEvent;
import dev.unrau.samsara.social.SocialService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.entity.AnimalTamer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;

import java.util.UUID;

/**
 * Keeps a tamed animal's death a local event.
 *
 * <p>Vanilla sends this one to the owner and to nobody else, wherever in the world the owner
 * happens to be. That is already the conservative answer, and it is left exactly as it is: the
 * plugin never widens it, and this listener never repeats it to the owner, because saying it twice
 * would be worse than saying it once.
 *
 * <p>What is added is the other half of "conservative" — the people who are standing there. A wolf
 * dying is a thing that happens in a place, and whoever is in that place has just watched it happen.
 * They are told, and nobody else is: not the server, and not the owner's contacts on the far side of
 * the map, because {@link SocialEvent#PET_DEATH} is the one event that does not travel along a
 * contact.
 *
 * <p>The recipient's ignore list still applies, and it applies to the <em>owner</em>: blocking
 * somebody blocks their animals too.
 *
 * <p>The message is built here rather than taken from the event, because Bukkit does not carry a
 * pet's death message. It is assembled from the same translation keys vanilla uses, so clients
 * render it in their own language and it reads as the line the game would have printed.
 */
public class PetDeathListener implements Listener {

    private final SocialService social;

    public PetDeathListener(SocialService social) {
        this.social = social;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (!social.settings().isEnabled() || !social.settings().isPetDeathsEnabled()) return;

        LivingEntity entity = event.getEntity();
        if (!(entity instanceof Tameable tameable) || !tameable.isTamed()) return;

        AnimalTamer owner = tameable.getOwner();
        if (owner == null) return;

        // A server that has switched death messages off has switched them off. Vanilla will not have
        // told the owner either, and this is not the place to overrule that.
        Location where = entity.getLocation();
        if (!Boolean.TRUE.equals(where.getWorld().getGameRuleValue(GameRule.SHOW_DEATH_MESSAGES))) return;

        UUID ownerId = owner.getUniqueId();
        Component message = deathMessage(entity).color(NamedTextColor.GRAY);

        if (social.settings().isPetTellOwner()) {
            Player onlineOwner = Bukkit.getPlayer(ownerId);
            if (onlineOwner != null) onlineOwner.sendMessage(message);
            social.audience().announceNear(where, ownerId, SocialEvent.PET_DEATH, message);
        } else {
            // The owner has already been told by the game itself; everyone else has to be nearby.
            social.audience().announceNear(where, ownerId, SocialEvent.PET_DEATH, message, ownerId);
        }
    }

    /**
     * A vanilla-shaped death line for an animal.
     *
     * <p>Named attackers first, because "your wolf was slain by Kyle" is the only version of this
     * anybody acts on. Everything else falls back through the causes vanilla has its own wording
     * for, and then to the generic line rather than to a guess.
     */
    private Component deathMessage(LivingEntity pet) {
        Component name = pet.name();

        Player killer = pet.getKiller();
        if (killer != null) {
            return Component.translatable("death.attack.player", name, killer.displayName());
        }

        EntityDamageEvent cause = pet.getLastDamageCause();
        if (cause instanceof EntityDamageByEntityEvent byEntity) {
            Entity damager = byEntity.getDamager();
            return Component.translatable("death.attack.mob", name, damager.name());
        }

        String key = cause == null ? "death.attack.generic" : keyFor(cause.getCause());
        return Component.translatable(key, name);
    }

    /** Vanilla's own wording for the ways something dies with nothing to blame it on. */
    private static String keyFor(EntityDamageEvent.DamageCause cause) {
        return switch (cause) {
            case FIRE -> "death.attack.inFire";
            case FIRE_TICK -> "death.attack.onFire";
            case LAVA -> "death.attack.lava";
            case HOT_FLOOR -> "death.attack.hotFloor";
            case SUFFOCATION -> "death.attack.inWall";
            case CRAMMING -> "death.attack.cramming";
            case DROWNING -> "death.attack.drown";
            case STARVATION -> "death.attack.starve";
            case DRYOUT -> "death.attack.dryout";
            case CONTACT -> "death.attack.cactus";
            case FALL -> "death.attack.fall";
            case FLY_INTO_WALL -> "death.attack.flyIntoWall";
            case VOID -> "death.attack.outOfWorld";
            case LIGHTNING -> "death.attack.lightningBolt";
            case MAGIC, POISON -> "death.attack.magic";
            case WITHER -> "death.attack.wither";
            case FALLING_BLOCK -> "death.attack.fallingBlock";
            case BLOCK_EXPLOSION, ENTITY_EXPLOSION -> "death.attack.explosion";
            case FREEZE -> "death.attack.freeze";
            case SONIC_BOOM -> "death.attack.sonic_boom";
            default -> "death.attack.generic";
        };
    }
}
