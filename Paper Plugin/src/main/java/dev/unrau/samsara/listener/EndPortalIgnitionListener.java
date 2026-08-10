package dev.unrau.samsara.listener;

import dev.unrau.samsara.config.PluginConfig;
import dev.unrau.samsara.service.Coord;
import dev.unrau.samsara.service.EndPortalShape;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.EndPortalFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Keeps the roar of an End portal opening where the portal is.
 *
 * <p>Vanilla plays that sound as a <em>global</em> level event, the same machinery the wither spawn
 * and the dragon's death use: the packet goes to every player on the server, and the client puts the
 * sound two blocks from your ear whatever the distance actually is. It is meant to be an
 * announcement. On a server whose entire premise is that lives are scattered a hundred thousand
 * blocks apart and nobody shares a front door, a noise that reaches everyone from anywhere is the
 * wrong noise — it tells the whole map that somebody, somewhere, found a stronghold.
 *
 * <p>There is no way to quieten a global level event after the fact, so the last eye is placed by
 * this listener instead of by the server: the interaction is cancelled, the eye goes into the frame,
 * the portal blocks are laid, and the roar is played as an ordinary positional sound that carries
 * {@code endPortal.activationSoundRadius} blocks and no further. Everything a player can see or hear
 * within earshot is unchanged; everything past it simply never hears about it.
 *
 * <p>Only the twelfth eye is handled here. The first eleven place nothing but themselves, make only
 * the short local click of a frame being filled, and are left entirely to the server. Cancellation
 * is honoured both ways round — a claim or protection plugin that cancels the interaction, or denies
 * the item, stops this listener as well, so a portal that vanilla would not have let the player
 * light is not lit here either.
 */
public class EndPortalIgnitionListener implements Listener {

    /** Blocks that a sound of volume 1 carries. Minecraft's range is this times the volume. */
    private static final float VANILLA_SOUND_RANGE = 16.0f;

    /** Vanilla plays both of these sounds unpitched, and so does this. */
    private static final float SOUND_PITCH = 1.0f;

    /**
     * The category vanilla files the activation roar under. Odd for a block sound, but it is what
     * players' volume sliders are already set up for, and this listener is changing how far the
     * sound reaches — not what it is.
     */
    private static final SoundCategory ACTIVATION_CATEGORY = SoundCategory.HOSTILE;

    private final PluginConfig config;

    public EndPortalIgnitionListener(PluginConfig config) {
        this.config = config;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEyeOfEnderPlaced(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.useItemInHand() == Event.Result.DENY) return;

        EquipmentSlot hand = event.getHand();
        ItemStack item = event.getItem();
        if (hand == null || item == null || item.getType() != Material.ENDER_EYE) return;

        Block clicked = event.getClickedBlock();
        if (clicked == null || clicked.getType() != Material.END_PORTAL_FRAME) return;
        if (!(clicked.getBlockData() instanceof EndPortalFrame frame) || frame.hasEye()) return;

        Player player = event.getPlayer();
        // The two modes that may not change the world. Vanilla stops them upstream of the item.
        if (player.getGameMode() == GameMode.ADVENTURE || player.getGameMode() == GameMode.SPECTATOR) return;

        Coord centre = centreCompletedBy(clicked);
        if (centre == null) return;

        // Past this point the server must not also place the eye, or the portal is lit twice and the
        // roar goes out globally after all.
        event.setCancelled(true);
        ignite(player, hand, clicked, frame, centre);
    }

    /**
     * The centre of the portal this eye would complete, or null if it would not complete one.
     *
     * <p>Null is the ordinary answer and the reason this listener is as quiet as it is: eleven eyes
     * out of twelve go in without the plugin touching them at all.
     */
    private Coord centreCompletedBy(Block clicked) {
        for (Coord centre : EndPortalShape.candidateCentres(new Coord(clicked.getX(), clicked.getZ()))) {
            if (ringIsCompleteBut(clicked, centre)) return centre;
        }
        return null;
    }

    /**
     * True if every frame of the ring around this centre stands, faces inward and holds an eye —
     * every frame except the one being clicked, which must be the empty slot that closes it.
     */
    private boolean ringIsCompleteBut(Block clicked, Coord centre) {
        World world = clicked.getWorld();
        int y = clicked.getY();

        for (Coord offset : EndPortalShape.frameOffsets()) {
            int x = centre.x() + offset.x();
            int z = centre.z() + offset.z();

            Block block = world.getBlockAt(x, y, z);
            if (!(block.getBlockData() instanceof EndPortalFrame frame)) return false;

            BlockFace facing = EndPortalShape.facingAt(offset.x(), offset.z());
            if (frame.getFacing() != facing) return false;

            boolean isTheOneBeingFilled = x == clicked.getX() && z == clicked.getZ();
            if (frame.hasEye() == isTheOneBeingFilled) return false;
        }
        return true;
    }

    /**
     * Does by hand what the server was about to do: the eye, its sound, the cost, the portal — and
     * then the roar, played to the neighbourhood rather than to the world.
     */
    private void ignite(Player player, EquipmentSlot hand, Block clicked, EndPortalFrame frame, Coord centre) {
        World world = clicked.getWorld();
        int y = clicked.getY();

        // Physics is left on so a comparator reading the frame sees its output change, which is the
        // one side effect vanilla bothers with here.
        frame.setEye(true);
        clicked.setBlockData(frame, true);
        world.playSound(clicked.getLocation().toCenterLocation(), Sound.BLOCK_END_PORTAL_FRAME_FILL,
            SoundCategory.BLOCKS, 1.0f, SOUND_PITCH);

        if (player.getGameMode() != GameMode.CREATIVE) {
            consumeOne(player, hand);
        }

        // Placed without physics, exactly as vanilla places them: a portal block asked to update
        // itself in mid-air is a portal block that removes itself.
        BlockData portal = Material.END_PORTAL.createBlockData();
        for (Coord offset : EndPortalShape.portalOffsets()) {
            world.getBlockAt(centre.x() + offset.x(), y, centre.z() + offset.z())
                .setBlockData(portal, false);
        }

        announce(world, new Location(world, centre.x() + 0.5, y + 0.5, centre.z() + 0.5));
    }

    /**
     * The activation roar, as a positional sound.
     *
     * <p>Minecraft has no separate range field: a sound carries sixteen blocks per point of volume,
     * and the client clamps what it actually plays, so a radius above sixteen reaches further
     * without being louder up close. The server only sends the packet to players inside that radius,
     * which is the whole point — the rest of the map is never told.
     */
    private void announce(World world, Location centre) {
        int radius = config.getEndPortalActivationSoundRadius();
        if (radius <= 0) return;

        world.playSound(centre, Sound.BLOCK_END_PORTAL_SPAWN, ACTIVATION_CATEGORY,
            radius / VANILLA_SOUND_RANGE, SOUND_PITCH);
    }

    /** Takes the eye the player just spent, from whichever hand they spent it from. */
    private void consumeOne(Player player, EquipmentSlot hand) {
        var inventory = player.getInventory();
        ItemStack held = inventory.getItem(hand);
        if (held == null || held.getType() != Material.ENDER_EYE) return;

        int left = held.getAmount() - 1;
        if (left <= 0) {
            inventory.setItem(hand, null);
            return;
        }
        held.setAmount(left);
        inventory.setItem(hand, held);
    }
}
