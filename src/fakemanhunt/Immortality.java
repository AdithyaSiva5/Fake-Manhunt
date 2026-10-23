package fakemanhunt;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageModifier;
import org.bukkit.command.CommandSender;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * /immortal - two modes:
 *   damage   - you take hits normally (knockback, hurt sound, red flash) but never drop below min-health.
 *              Looks legit in PvP.
 *   nodamage - every hit is cancelled, no hurt effect at all. Hides a fall that should have killed you.
 */
final class Immortality implements Listener {

    enum Mode { DAMAGE, NODAMAGE }

    private final FakeManhunt plugin;
    // Kept in the plugin folder (outside the world folder) so /load restarts don't reset it.
    private final File file;
    private final Map<UUID, Mode> immortal = new HashMap<>();

    Immortality(FakeManhunt plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "immortal-players.yml");
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection modes = yml.getConfigurationSection("modes");
        if (modes != null) {
            for (String id : modes.getKeys(false)) {
                immortal.put(UUID.fromString(id), Mode.valueOf(modes.getString(id, "DAMAGE")));
            }
        }
        // Older versions only stored a list of players, all in damage mode.
        yml.getStringList("immortal").forEach(s -> immortal.putIfAbsent(UUID.fromString(s), Mode.DAMAGE));
    }

    /** /immortal [damage|nodamage|off] [player]; with no mode it toggles damage mode. */
    void toggle(CommandSender sender, String[] args) {
        Mode mode = null;
        boolean explicit = false;
        int i = 0;
        if (args.length > 0) {
            switch (args[0].toLowerCase()) {
                case "damage", "hurt" -> { mode = Mode.DAMAGE; explicit = true; i = 1; }
                case "nodamage", "god" -> { mode = Mode.NODAMAGE; explicit = true; i = 1; }
                case "off" -> { explicit = true; i = 1; }
                default -> { }
            }
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
            sender.sendMessage("§cUsage: /immortal [damage|nodamage|off] <player>");
            return;
        }

        UUID id = target.getUniqueId();
        if (!explicit) mode = immortal.containsKey(id) ? null : Mode.DAMAGE;
        if (mode == null) immortal.remove(id); else immortal.put(id, mode);
        YamlConfiguration yml = new YamlConfiguration();
        immortal.forEach((u, m) -> yml.set("modes." + u, m.name()));
        try {
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save " + file.getName() + ": " + e);
        }

        String state = mode == null ? "§cOFF"
                : mode == Mode.DAMAGE ? "§aON - damage §7(you take hits but can't die)"
                : "§aON - nodamage §7(hits don't affect you at all)";
        target.sendMessage("§6[Immortal] " + state);
        if (sender != target) sender.sendMessage("§6[Immortal] " + target.getName() + ": " + state);
    }

    // Runs last so it sees the final damage after armor, enchants, etc.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    @SuppressWarnings("deprecation")
    public void onDamage(EntityDamageEvent e) {
        if (!plugin.getConfig().getBoolean("immortal.enabled")) return;
        if (!(e.getEntity() instanceof Player p)) return;
        Mode mode = immortal.get(p.getUniqueId());
        if (mode == null) return;
        // /kill still works (if allowed), so you can get out of the void.
        if (e.getCause() == EntityDamageEvent.DamageCause.KILL
                && plugin.getConfig().getBoolean("immortal.allow-kill-command")) return;

        if (mode == Mode.NODAMAGE) {
            // No hurt sound, knockback or red flash, so nobody can tell.
            e.setCancelled(true);
            return;
        }

        double minHealth = Math.max(0.5, plugin.getConfig().getDouble("immortal.min-health"));
        double health = p.getHealth();
        if (health - e.getFinalDamage() >= minHealth) return;

        // Lethal hit: still take it (hurt sound, knockback, red flash) but only down to min-health.
        for (DamageModifier m : DamageModifier.values()) {
            if (m != DamageModifier.BASE && e.isApplicable(m)) e.setDamage(m, 0);
        }
        e.setDamage(DamageModifier.BASE, Math.max(0, health - minHealth));
    }
}
