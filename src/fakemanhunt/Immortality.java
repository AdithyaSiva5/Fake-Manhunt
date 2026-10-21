package fakemanhunt;

import org.bukkit.Bukkit;
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
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** /immortal - take damage normally but never drop below the configured health. */
final class Immortality implements Listener {

    private final FakeManhunt plugin;
    // Kept in the plugin folder (outside the world folder) so /load restarts don't reset it.
    private final File file;
    private final Set<UUID> immortal = new HashSet<>();

    Immortality(FakeManhunt plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "immortal-players.yml");
        YamlConfiguration.loadConfiguration(file).getStringList("immortal")
                .forEach(s -> immortal.add(UUID.fromString(s)));
    }

    void toggle(CommandSender sender, String[] args) {
        Player target;
        if (args.length > 0) {
            target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                sender.sendMessage("§cPlayer not found: " + args[0]);
                return;
            }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            sender.sendMessage("§cUsage: /immortal <player>");
            return;
        }

        boolean on = immortal.add(target.getUniqueId());
        if (!on) immortal.remove(target.getUniqueId());
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("immortal", immortal.stream().map(UUID::toString).toList());
        try {
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save " + file.getName() + ": " + e);
        }

        String state = on ? "§aON §7(you take damage but can't die)" : "§cOFF";
        target.sendMessage("§6[Immortal] " + state);
        if (sender != target) sender.sendMessage("§6[Immortal] " + target.getName() + ": " + state);
    }

    // Runs last so it sees the final damage after armor, enchants, etc.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    @SuppressWarnings("deprecation")
    public void onDamage(EntityDamageEvent e) {
        if (!plugin.getConfig().getBoolean("immortal.enabled")) return;
        if (!(e.getEntity() instanceof Player p) || !immortal.contains(p.getUniqueId())) return;
        // /kill still works (if allowed), so you can get out of the void.
        if (e.getCause() == EntityDamageEvent.DamageCause.KILL
                && plugin.getConfig().getBoolean("immortal.allow-kill-command")) return;

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
