package fakemanhunt;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * /qsave and /qload - save and restore just the players (position, health, hunger,
 * saturation, inventory and armor, XP, effects) plus the time and weather.
 * The world itself is not touched, so it's instant and nobody gets kicked.
 */
final class QuickSave {

    private final FakeManhunt plugin;
    // Kept in the plugin folder so it survives /load and restarts.
    private final File file;
    private YamlConfiguration data;

    QuickSave(FakeManhunt plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "quicksave.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
    }

    void save(CommandSender sender) {
        YamlConfiguration yml = new YamlConfiguration();
        for (Player p : Bukkit.getOnlinePlayers()) {
            ConfigurationSection s = yml.createSection("players." + p.getUniqueId());
            s.set("name", p.getName());
            s.set("location", p.getLocation());
            s.set("gamemode", p.getGameMode().name());
            s.set("health", p.getHealth());
            s.set("food", p.getFoodLevel());
            s.set("saturation", (double) p.getSaturation());
            s.set("exhaustion", (double) p.getExhaustion());
            s.set("fire-ticks", p.getFireTicks());
            s.set("air", p.getRemainingAir());
            s.set("level", p.getLevel());
            s.set("exp", (double) p.getExp());
            s.set("inventory", copy(p.getInventory().getContents()));
            s.set("ender-chest", copy(p.getEnderChest().getContents()));
            s.set("effects", List.copyOf(p.getActivePotionEffects()));
        }
        for (World w : Bukkit.getWorlds()) {
            ConfigurationSection s = yml.createSection("worlds." + w.getName());
            s.set("time", w.getFullTime());
            s.set("storm", w.hasStorm());
            s.set("thunder", w.isThundering());
            s.set("weather-duration", w.getWeatherDuration());
        }
        data = yml;
        try {
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not write " + file.getName() + ": " + e);
        }
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        Bukkit.broadcastMessage("§a[Manhunt] Quick save at " + time + " ("
                + Bukkit.getOnlinePlayers().size() + " players). /qload will return here.");
    }

    /**
     * getContents() hands back live views of the stacks, so anything that changes the inventory
     * later would change the save too. Store copies, as a List so /qload can read it back
     * without a restart.
     */
    private static List<ItemStack> copy(ItemStack[] items) {
        List<ItemStack> out = new java.util.ArrayList<>(items.length);
        for (ItemStack i : items) out.add(i == null ? null : i.clone());
        return out;
    }

    /** Restores every saved player who is online, or just {@code only} if given. */
    void load(CommandSender sender, String only) {
        ConfigurationSection players = data.getConfigurationSection("players");
        if (players == null) {
            sender.sendMessage("§c[Manhunt] No quick save found. Use /qsave first.");
            return;
        }
        if (only != null) {
            Player p = Bukkit.getPlayerExact(only);
            if (p == null) {
                sender.sendMessage("§cPlayer not found: " + only);
            } else if (!players.isConfigurationSection(p.getUniqueId().toString())) {
                sender.sendMessage("§c[Manhunt] " + p.getName() + " isn't in the quick save.");
            } else {
                restore(p, players.getConfigurationSection(p.getUniqueId().toString()));
                sender.sendMessage("§a[Manhunt] Quick-loaded " + p.getName() + ".");
            }
            return;
        }

        int restored = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            ConfigurationSection s = players.getConfigurationSection(p.getUniqueId().toString());
            if (s == null) {
                p.sendMessage("§7[Manhunt] You weren't in the quick save, so you were left as is.");
                continue;
            }
            if (restore(p, s)) restored++;
        }
        ConfigurationSection worlds = data.getConfigurationSection("worlds");
        if (worlds != null) {
            for (String name : worlds.getKeys(false)) {
                World w = Bukkit.getWorld(name);
                if (w == null) continue;
                ConfigurationSection s = worlds.getConfigurationSection(name);
                w.setFullTime(s.getLong("time"));
                w.setStorm(s.getBoolean("storm"));
                w.setThundering(s.getBoolean("thunder"));
                w.setWeatherDuration(Math.max(1, s.getInt("weather-duration")));
            }
        }
        Bukkit.broadcastMessage("§a[Manhunt] Quick load done (" + restored + " players).");
    }

    @SuppressWarnings("unchecked")
    private boolean restore(Player p, ConfigurationSection s) {
        Location loc = s.getLocation("location");
        if (loc == null || loc.getWorld() == null) {
            p.sendMessage("§c[Manhunt] Your quick save points to a world that no longer exists.");
            return false;
        }
        if (p.isDead()) p.spigot().respawn();
        p.leaveVehicle();
        p.teleport(loc);
        p.setVelocity(new Vector());
        p.setFallDistance(0);

        p.setGameMode(GameMode.valueOf(s.getString("gamemode", "SURVIVAL")));
        p.setHealth(Math.min(s.getDouble("health", 20), p.getAttribute(Attribute.MAX_HEALTH).getValue()));
        p.setFoodLevel(s.getInt("food", 20));
        p.setSaturation((float) s.getDouble("saturation", 5));
        p.setExhaustion((float) s.getDouble("exhaustion", 0));
        p.setFireTicks(s.getInt("fire-ticks", 0));
        p.setRemainingAir(s.getInt("air", p.getMaximumAir()));
        p.setLevel(s.getInt("level", 0));
        p.setExp((float) s.getDouble("exp", 0));

        p.getInventory().setContents(((List<ItemStack>) s.getList("inventory", List.of())).toArray(new ItemStack[0]));
        p.getEnderChest().setContents(((List<ItemStack>) s.getList("ender-chest", List.of())).toArray(new ItemStack[0]));
        p.updateInventory();

        p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
        for (Object o : s.getList("effects", List.of())) {
            if (o instanceof PotionEffect e) p.addPotionEffect(e);
        }
        return true;
    }
}
