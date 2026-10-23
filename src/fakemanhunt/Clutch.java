package fakemanhunt;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.WindCharge;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * /clutch - makes every clutch land. A right-click with a clutch item while falling
 * (even a bit too early, slightly off the block, or a held click that repeats too slowly)
 * is remembered, and the server does the clutch itself right before the player lands:
 * places the water / powder snow / block, drops the boat, or throws the pearl / wind charge
 * straight down. Fall damage from that landing is cancelled, so lag can't ruin it.
 */
final class Clutch implements Listener {

    private enum Kind { WATER, POWDER_SNOW, BLOCK, BOAT, PEARL, WIND_CHARGE }

    private record Armed(EquipmentSlot hand, Material item, Kind kind, int expires) { }
    private record Ground(Block block, double dist) { }
    private record Mount(Entity boat, int expires) { }

    private static final Set<Material> BLOCKS = EnumSet.of(Material.SLIME_BLOCK, Material.HAY_BLOCK,
            Material.HONEY_BLOCK, Material.COBWEB, Material.SCAFFOLDING, Material.TWISTING_VINES);

    private final FakeManhunt plugin;
    // Kept in the plugin folder (outside the world folder) so /load restarts don't reset it.
    private final File file;
    private final Set<UUID> enabled = new HashSet<>();
    private final Map<UUID, Armed> armed = new HashMap<>();
    private final Map<UUID, Integer> safeUntil = new HashMap<>();
    private final Map<UUID, Mount> mounts = new HashMap<>();
    // Server-side fall speed in blocks/tick, measured from position changes (player velocity isn't reliable).
    private final Map<UUID, double[]> lastY = new HashMap<>();

    Clutch(FakeManhunt plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "clutch-players.yml");
        YamlConfiguration.loadConfiguration(file).getStringList("players")
                .forEach(s -> enabled.add(UUID.fromString(s)));
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    /** /clutch [on|off] [player]; with no mode it toggles. */
    void toggle(CommandSender sender, String[] args) {
        Boolean on = null;
        int i = 0;
        if (args.length > 0 && (args[0].equalsIgnoreCase("on") || args[0].equalsIgnoreCase("off"))) {
            on = args[0].equalsIgnoreCase("on");
            i = 1;
        }

        Player target;
        if (args.length > i) {
            target = Bukkit.getPlayerExact(args[i]);
            if (target == null) {
                sender.sendMessage("§cPlayer not found: " + args[i]);
                return;
            }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            sender.sendMessage("§cUsage: /clutch [on|off] <player>");
            return;
        }

        UUID id = target.getUniqueId();
        if (on == null) on = !enabled.contains(id);
        if (on) enabled.add(id); else enabled.remove(id);
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("players", enabled.stream().map(UUID::toString).toList());
        try {
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save " + file.getName() + ": " + e);
        }

        String state = on ? "§aON §7(every clutch lands)" : "§cOFF";
        target.sendMessage("§6[Clutch] " + state);
        if (sender != target) sender.sendMessage("§6[Clutch] " + target.getName() + ": " + state);
    }

    private static Kind kindOf(Material m) {
        if (m == Material.WATER_BUCKET) return Kind.WATER;
        if (m == Material.POWDER_SNOW_BUCKET) return Kind.POWDER_SNOW;
        if (m == Material.ENDER_PEARL) return Kind.PEARL;
        if (m == Material.WIND_CHARGE) return Kind.WIND_CHARGE;
        if (BLOCKS.contains(m)) return Kind.BLOCK;
        if (m.name().endsWith("_BOAT") || m.name().endsWith("_RAFT")) return Kind.BOAT;
        return null;
    }

    private boolean active(Player p) {
        return plugin.getConfig().getBoolean("clutch.enabled", true) && enabled.contains(p.getUniqueId());
    }

    // Not ignoreCancelled: right-clicks on air arrive already cancelled.
    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        ItemStack item = e.getItem();
        if (item == null || e.getHand() == null || !active(p)) return;
        Kind kind = kindOf(item.getType());
        if (kind == null) return;
        UUID id = p.getUniqueId();
        if (armed.containsKey(id)) {
            e.setCancelled(true); // held click repeating; the clutch is already queued
            return;
        }
        if (p.isOnGround() || p.isFlying() || p.isGliding() || p.isInWater() || p.getVehicle() != null) return;
        if (p.hasCooldown(item.getType())) return;
        // Pearls and wind charges get used up on the first click, so a held click from high up
        // would waste them; any click while falling counts and they're thrown at the right moment.
        boolean thrown = kind == Kind.PEARL || kind == Kind.WIND_CHARGE;
        Ground g = ground(p, thrown ? 128 : window(p));
        if (g == null) return;
        // Only step in when the landing would actually hurt.
        if (p.getFallDistance() + g.dist() <= 3.5) return;

        e.setCancelled(true); // the server does the clutch itself, at the right moment
        Armed a = new Armed(e.getHand(), item.getType(), kind, Bukkit.getCurrentTick() + (thrown ? 200 : 100));
        armed.put(id, a);
        tryTrigger(p, a);
    }

    private void tick() {
        int now = Bukkit.getCurrentTick();
        for (Iterator<Map.Entry<UUID, Armed>> it = armed.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            Player p = Bukkit.getPlayer(en.getKey());
            if (p == null || p.isOnGround() || p.isInWater() || p.getVehicle() != null || now > en.getValue().expires()) {
                it.remove();
            } else if (tryTrigger(p, en.getValue())) {
                it.remove();
            }
        }
        for (Iterator<Map.Entry<UUID, Mount>> it = mounts.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            Player p = Bukkit.getPlayer(en.getKey());
            Entity boat = en.getValue().boat();
            if (p == null || !boat.isValid() || now > en.getValue().expires()) {
                it.remove();
            } else if (p.getBoundingBox().expand(0.3).overlaps(boat.getBoundingBox())) {
                boat.addPassenger(p);
                it.remove();
            }
        }
        safeUntil.values().removeIf(t -> t < now);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!enabled.contains(p.getUniqueId())) continue;
            double y = p.getLocation().getY();
            double[] prev = lastY.computeIfAbsent(p.getUniqueId(), k -> new double[]{y, 0});
            prev[1] = prev[0] - y;
            prev[0] = y;
        }
    }

    /** How early a click still counts, in blocks above the ground. */
    private double window(Player p) {
        return Math.max(plugin.getConfig().getDouble("clutch.window-blocks", 6),
                speed(p) * plugin.getConfig().getInt("clutch.window-ticks", 10));
    }

    private double speed(Player p) {
        double[] s = lastY.get(p.getUniqueId());
        return s == null ? 0 : Math.max(0, s[1]);
    }

    /** Does the clutch once the player is about to land (next tick would hit the ground). */
    private boolean tryTrigger(Player p, Armed a) {
        Ground g = ground(p, window(p));
        if (g == null) return false;
        if (g.dist() > Math.max(2.0, speed(p) * 1.2 + 0.3)) return false;
        ItemStack item = p.getInventory().getItem(a.hand());
        if (item.getType() != a.item()) return true; // switched items; give up
        if (perform(p, a, item, g.block().getRelative(BlockFace.UP))) {
            p.swingHand(a.hand());
            safeUntil.put(p.getUniqueId(), Bukkit.getCurrentTick() + 40);
        }
        return true;
    }

    private boolean perform(Player p, Armed a, ItemStack item, Block target) {
        boolean free = target.getType().isAir() || target.isReplaceable();
        boolean creative = p.getGameMode() == GameMode.CREATIVE;
        Location at = target.getLocation().add(0.5, 0, 0.5);
        switch (a.kind()) {
            case WATER, POWDER_SNOW -> {
                if (!free) return false;
                if (a.kind() == Kind.WATER && p.getWorld().isUltraWarm()) return false; // evaporates, like vanilla
                boolean water = a.kind() == Kind.WATER;
                target.setType(water ? Material.WATER : Material.POWDER_SNOW);
                p.getWorld().playSound(at, water ? Sound.ITEM_BUCKET_EMPTY : Sound.ITEM_BUCKET_EMPTY_POWDER_SNOW, 1, 1);
                if (!creative) p.getInventory().setItem(a.hand(), new ItemStack(Material.BUCKET));
                if (water && plugin.getConfig().getBoolean("clutch.auto-pickup-water")) pickUpLater(p, a.hand(), target);
            }
            case BLOCK -> {
                if (!free) return false;
                target.setType(a.item());
                p.getWorld().playSound(at, target.getBlockData().getSoundGroup().getPlaceSound(), 1, 0.8f);
                if (!creative) item.setAmount(item.getAmount() - 1);
            }
            case BOAT -> {
                if (!free) return false;
                EntityType type;
                try {
                    type = EntityType.valueOf(a.item().name());
                } catch (IllegalArgumentException ex) {
                    return false;
                }
                at.setYaw(p.getLocation().getYaw());
                Entity boat = p.getWorld().spawnEntity(at, type);
                if (!creative) item.setAmount(item.getAmount() - 1);
                if (plugin.getConfig().getBoolean("clutch.boat-auto-enter", true)) {
                    mounts.put(p.getUniqueId(), new Mount(boat, Bukkit.getCurrentTick() + 20));
                }
            }
            case PEARL -> {
                p.launchProjectile(EnderPearl.class, new Vector(0, -3, 0));
                p.getWorld().playSound(p.getLocation(), Sound.ENTITY_ENDER_PEARL_THROW, 0.5f, 0.4f);
                p.setCooldown(Material.ENDER_PEARL, 20);
                if (!creative) item.setAmount(item.getAmount() - 1);
            }
            case WIND_CHARGE -> {
                p.launchProjectile(WindCharge.class, new Vector(0, -3, 0));
                p.getWorld().playSound(p.getLocation(), Sound.ENTITY_WIND_CHARGE_THROW, 0.5f, 0.4f);
                p.setCooldown(Material.WIND_CHARGE, 10);
                if (!creative) item.setAmount(item.getAmount() - 1);
            }
        }
        return true;
    }

    private void pickUpLater(Player p, EquipmentSlot hand, Block water) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline() || water.getType() != Material.WATER) return;
            if (p.getInventory().getItem(hand).getType() != Material.BUCKET) return;
            water.setType(Material.AIR);
            p.getInventory().setItem(hand, new ItemStack(Material.WATER_BUCKET));
            p.getWorld().playSound(water.getLocation(), Sound.ITEM_BUCKET_FILL, 1, 1);
            p.swingHand(hand);
        }, Math.max(1, plugin.getConfig().getInt("clutch.pickup-delay-ticks", 10)));
    }

    /**
     * Nearest ground under any part of the player's hitbox (centre and four corners), so landing
     * half off a block still counts. Null if there's nothing solid close enough, or it's already
     * water (no clutch needed) or lava.
     */
    private Ground ground(Player p, double range) {
        Location feet = p.getLocation();
        double max = range + 4;
        Ground best = null;
        for (double[] o : new double[][]{{0, 0}, {0.29, 0.29}, {0.29, -0.29}, {-0.29, 0.29}, {-0.29, -0.29}}) {
            Location start = feet.clone().add(o[0], 0.1, o[1]);
            RayTraceResult r = p.getWorld().rayTraceBlocks(start, new Vector(0, -1, 0), max,
                    FluidCollisionMode.ALWAYS, true);
            if (r == null || r.getHitBlock() == null) continue;
            double dist = start.getY() - 0.1 - r.getHitPosition().getY();
            if (best == null || dist < best.dist()) best = new Ground(r.getHitBlock(), dist);
        }
        if (best == null || best.block().isLiquid()) return null;
        return best;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFall(EntityDamageEvent e) {
        if (e.getCause() != EntityDamageEvent.DamageCause.FALL) return;
        if (e.getEntity() instanceof Player p && safeUntil.containsKey(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        armed.remove(id);
        mounts.remove(id);
        lastY.remove(id);
    }
}
