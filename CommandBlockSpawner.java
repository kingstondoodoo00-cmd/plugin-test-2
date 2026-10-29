package com.example.cbspawner;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class CommandBlockSpawner extends JavaPlugin implements Listener {

    // The one command block this plugin cares about
    private static final int X = 500;
    private static final int Y = 150;
    private static final int Z = 500;

    /** Upward speed given to the player when the mace is first created. */
    private static final double LAUNCH_VELOCITY = 1.5;
    /** How long (in ticks) we watch a dropped mace before giving up. */
    private static final int WATCH_TICKS = 200;

    // ---- Ability 1: Hooks (shockwave) ----
    /** How far the shockwave reaches, in blocks. */
    private static final double SHOCK_RADIUS = 4.0;
    /** Sideways push on nearby players. */
    private static final double SHOCK_PUSH = 1.2;
    /** Upward pop on nearby players. */
    private static final double SHOCK_PUSH_UP = 0.4;
    /** Upward speed the mace holder gets from the shockwave (1.5 is roughly 12 blocks high). */
    private static final double SHOCK_SELF_LAUNCH = 1.5;

    // ---- Hook (right-click) ----
    /** How far the hook flies, in blocks, in the direction you're looking. */
    private static final double HOOK_RANGE = 25.0;
    /** How fast the black line shoots out, in blocks per tick. */
    private static final double HOOK_LINE_SPEED = 3.0;
    /** Extra upward speed added to the hook pull (0.9 is roughly 5 blocks of lift). */
    private static final double HOOK_LIFT = 0.9;
    /** Time between hook shots for one player. */
    private static final long HOOK_COOLDOWN_MS = 2000;
    /** Base pull speed toward the hook, plus a bit more per block of distance. */
    private static final double HOOK_PULL_BASE = 0.5;
    private static final double HOOK_PULL_PER_BLOCK = 0.06;
    /** Cap on the pull speed so you don't get flung across the map. */
    private static final double HOOK_PULL_MAX = 2.2;
    /** Minimum fall (blocks) for a hit on a player to count (same as a vanilla smash). */
    private static final double MIN_SMASH_FALL = 1.5;
    /** Time between shockwaves for one player. */
    private static final long SHOCK_COOLDOWN_MS = 1000;

    private NamespacedKey maceKey;
    private final Map<UUID, Long> lastShock = new HashMap<>();
    private final Map<UUID, Long> lastHook = new HashMap<>();

    @Override
    public void onEnable() {
        maceKey = new NamespacedKey(this, "command_block_mace");
        getServer().getPluginManager().registerEvents(this, this);
    }

    /** The world the command block lives in (the main world). */
    private World world() {
        return getServer().getWorlds().get(0);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        World world = world();

        if (Y < world.getMinHeight() || Y >= world.getMaxHeight()) {
            sender.sendMessage("§cY=" + Y + " is outside this world's build limits.");
            return true;
        }

        new Location(world, X, Y, Z).getBlock().setType(Material.COMMAND_BLOCK);
        sender.sendMessage("§aPlaced a command block at " + X + " " + Y + " " + Z + " in " + world.getName() + ".");
        sender.sendMessage("§7Drop a mace on it to get the Command Block Mace.");
        return true;
    }

    // ---- Dropping a mace on the command block ----

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        Item item = event.getItemDrop();
        // Only a mace counts. Everything else is ignored.
        if (item.getItemStack().getType() != Material.MACE) return;

        UUID playerId = event.getPlayer().getUniqueId();

        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                ticks += 2;
                if (!item.isValid() || ticks > WATCH_TICKS) {
                    cancel();
                    return;
                }
                if (isOnCommandBlock(item)) {
                    cancel();
                    Player player = getServer().getPlayer(playerId);
                    if (player != null && player.isOnline()) {
                        trigger(player, item);
                    }
                }
            }
        }.runTaskTimer(this, 2L, 2L);
    }

    /** True if the item is resting on top of the command block at X Y Z. */
    private boolean isOnCommandBlock(Item item) {
        Location loc = item.getLocation();
        if (!loc.getWorld().equals(world())) return false;
        return loc.getBlockX() == X
                && loc.getBlockZ() == Z
                && loc.getBlockY() == Y + 1
                && loc.getWorld().getBlockAt(X, Y, Z).getType() == Material.COMMAND_BLOCK;
    }

    private void trigger(Player player, Item droppedMace) {
        droppedMace.remove(); // the dropped mace is consumed

        player.setVelocity(new Vector(0, LAUNCH_VELOCITY, 0));

        var leftovers = player.getInventory().addItem(createCommandBlockMace());
        // Inventory full? Drop it at the player's feet.
        leftovers.values().forEach(i -> player.getWorld().dropItemNaturally(player.getLocation(), i));

        player.sendMessage(Component.text("You received the Command Block Mace!", NamedTextColor.GOLD));
    }

    private ItemStack createCommandBlockMace() {
        ItemStack mace = new ItemStack(Material.MACE);
        ItemMeta meta = mace.getItemMeta();
        meta.displayName(Component.text("Command Block Mace", NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("Ability: Hooks (right-click)", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)));
        meta.addEnchant(Enchantment.DENSITY, 5, true);
        meta.addEnchant(Enchantment.WIND_BURST, 1, true);
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(maceKey, PersistentDataType.BYTE, (byte) 1);
        mace.setItemMeta(meta);
        return mace;
    }

    private boolean isCommandBlockMace(ItemStack stack) {
        if (stack == null || stack.getType() != Material.MACE || !stack.hasItemMeta()) return false;
        return stack.getItemMeta().getPersistentDataContainer().has(maceKey, PersistentDataType.BYTE);
    }

    // ---- Ability 1: Hooks ----

    /** Hitting a player with the mace while falling (landing on the ground does nothing). */
    @EventHandler(ignoreCancelled = true)
    public void onMaceHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof Player victim)) return;
        if (!isCommandBlockMace(attacker.getInventory().getItemInMainHand())) return;
        if (attacker.getFallDistance() < MIN_SMASH_FALL) return;

        shockwave(attacker, victim.getLocation());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastShock.remove(event.getPlayer().getUniqueId());
        lastHook.remove(event.getPlayer().getUniqueId());
    }

    /** Pushes nearby players away from the center and throws the user into the air. */
    private void shockwave(Player user, Location center) {
        UUID id = user.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = lastShock.get(id);
        if (last != null && now - last < SHOCK_COOLDOWN_MS) return;
        lastShock.put(id, now);

        World world = center.getWorld();

        for (Player other : world.getNearbyPlayers(center, SHOCK_RADIUS)) {
            if (other.equals(user)) continue;

            Vector push = other.getLocation().toVector().subtract(center.toVector()).setY(0);
            if (push.lengthSquared() < 0.0001) {
                push = user.getLocation().getDirection().setY(0); // standing exactly on the center
            }
            other.setVelocity(push.normalize().multiply(SHOCK_PUSH).setY(SHOCK_PUSH_UP));
        }

        user.setVelocity(new Vector(0, SHOCK_SELF_LAUNCH, 0));

        world.spawnParticle(Particle.CLOUD, center, 30, 1.5, 0.1, 1.5, 0.05);
        world.playSound(center, Sound.ITEM_MACE_SMASH_GROUND, 1.0f, 1.0f);
    }

    // ---- Hook: right-click to shoot a black line and get pulled to where it lands ----

    @EventHandler
    public void onRightClick(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        if (!isCommandBlockMace(player.getInventory().getItemInMainHand())) return;

        fireHook(player);
    }

    private void fireHook(Player user) {
        UUID id = user.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = lastHook.get(id);
        if (last != null && now - last < HOOK_COOLDOWN_MS) return;
        lastHook.put(id, now);

        World world = user.getWorld();
        Location eye = user.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        Vector start = eye.toVector();

        RayTraceResult hit = world.rayTrace(
                eye, dir, HOOK_RANGE, FluidCollisionMode.NEVER, true, 0.2,
                entity -> !entity.equals(user));

        boolean landed = hit != null && hit.getHitPosition() != null;
        Vector end = landed ? hit.getHitPosition() : start.clone().add(dir.clone().multiply(HOOK_RANGE));
        double totalLength = start.distance(end);

        world.playSound(eye, Sound.ENTITY_FISHING_BOBBER_THROW, 1.0f, 0.8f);

        new BukkitRunnable() {
            double reach = 0;
            boolean pulled = false;
            int pullTicks = 0;

            @Override
            public void run() {
                if (!user.isOnline() || !user.getWorld().equals(world)) {
                    cancel();
                    return;
                }

                // The line starts at the user (so it follows them while they fly) and ends at the tip.
                Vector origin = user.getLocation().toVector().add(new Vector(0, 1.0, 0));
                reach = Math.min(totalLength, reach + HOOK_LINE_SPEED);
                Vector tip = start.clone().add(dir.clone().multiply(reach));
                drawLine(world, origin, tip);

                if (reach < totalLength) return; // still shooting out

                if (!landed) { // hit nothing: the line just disappears
                    cancel();
                    return;
                }

                if (!pulled) {
                    pulled = true;
                    user.setVelocity(hookPull(user, end));
                    world.playSound(end.toLocation(world), Sound.BLOCK_CHAIN_PLACE, 1.0f, 0.8f);
                }

                pullTicks++;
                if (pullTicks > 8) cancel(); // keep drawing the line for a few ticks while you fly
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    /** Velocity that pulls the user toward the point where the hook landed. */
    private Vector hookPull(Player user, Vector landing) {
        Vector toHook = landing.clone().subtract(user.getLocation().toVector());
        double distance = toHook.length();
        if (distance < 1.0) {
            return new Vector(0, HOOK_LIFT, 0);
        }

        double speed = Math.min(HOOK_PULL_MAX, HOOK_PULL_BASE + distance * HOOK_PULL_PER_BLOCK);
        Vector pull = toHook.normalize().multiply(speed);
        pull.setY(pull.getY() + HOOK_LIFT); // still gets lifted off the ground
        return pull;
    }

    /** Draws a black line of particles between two points. */
    private void drawLine(World world, Vector from, Vector to) {
        // (1,1,1) instead of pure black, because pure 0,0,0 dust can render as red
        Particle.DustOptions black = new Particle.DustOptions(Color.fromRGB(1, 1, 1), 1.3f);

        Vector step = to.clone().subtract(from);
        double length = step.length();
        if (length < 0.01) return;
        step.normalize().multiply(0.4);

        Vector point = from.clone();
        for (double d = 0; d < length; d += 0.4) {
            world.spawnParticle(Particle.DUST, point.getX(), point.getY(), point.getZ(), 1, 0, 0, 0, 0, black);
            point.add(step);
        }
    }
}
